# Redis Resilient Initial Microservice (Quarkus)

Microservicio REST desarrollado en **Quarkus 3.15.1** con **Java 21**, diseñado para interactuar con **Redis** implementando resiliencia activa, degradación graceful a memoria local (**Caffeine**), detección de degradación de latencia SLA y reseteo proactivo de conexiones.

La arquitectura sigue un modelo de **programación imperativa síncrona** sin librerías reactivas como Mutiny.

---

## 1. Explicación de la Arquitectura y Código Actual

El flujo de una petición y los componentes del microservicio se estructuran en capas desacopladas:

```
[Cliente HTTP] 
      │
      ▼
[CacheResource] (JAX-RS / Quarkus REST - Imperativo)
      │
      ▼
[CacheService] (Lógica de Caché y Fault Tolerance)
      ├──> [Caffeine Local Cache] (Fallback acotado con TTL dinámico)
      └──> [RedisPoolManager] (Gestión y resiliencia de sockets)
                 │
                 ▼
         [Vert.x Core RedisAPI] ──> [ToxiProxy :6380] ──> [Redis :6379]
```

### Detalle de Clases y Responsabilidades

#### A. Capa de Presentación / API
* **[`CacheResource.java`](src/main/java/com/company/redis/api/CacheResource.java)**:
  * Expone los endpoints REST (`POST /api/v1/cache`, `GET /api/v1/cache/{key}`, etc.).
  * Valida las entradas (clave no vacía, TTL positivo).
  * Retorna directamente objetos `Response` de JAX-RS de forma síncrona.
  * Mapea el resultado: si una escritura se degrada a memoria local responde `202 Accepted` con estado `DEGRADED_STORED_IN_LOCAL_FALLBACK`; si persiste en Redis responde `200 OK` con `STORED_IN_REDIS`.
  * Expone endpoints administrativos: `/pool/reset` (protegido por header `X-Admin-Key`) y `/local-cache/clear`.

#### B. Capa de Servicio y Resiliencia
* **[`CacheService.java`](src/main/java/com/company/redis/service/CacheService.java)**:
  * Encapsula la estrategia de caché híbrida (Redis + Memoria Local).
  * **Caché en Memoria (`Caffeine`)**: Configurada con tamaño acotado (máximo 10,000 entradas) y una política de expiración dinámica (`Expiry`) que respeta el `ttlSeconds` individual de cada entrada.
  * **Tolerancia a Fallos Declarativa**: Integra `@CircuitBreaker` y `@Fallback` de MicroProfile Fault Tolerance. Si Redis falla o el circuito está abierto, el método `setFallback()` o `getFallback()` atiende la solicitud usando la memoria local, evitando errores 500 al cliente.

* **[`CacheEntry.java`](src/main/java/com/company/redis/service/CacheEntry.java) y [`CacheWriteResult.java`](src/main/java/com/company/redis/service/CacheWriteResult.java)**:
  * Registros inmutables (records de Java) que transportan el valor en caché, el indicador `fromFallback`, su TTL y el estado de degradación (`isDegraded()`).

#### C. Capa de Infraestructura y Conexión
* **[`RedisPoolManager.java`](src/main/java/com/company/redis/infrastructure/RedisPoolManager.java)**:
  * Inicializa el cliente core de Redis (`io.vertx.redis.client.Redis` y `RedisAPI`) a partir de la configuración oficial de Quarkus (`quarkus.redis.hosts`, etc.).
  * **`executeTimed()`**: Envuelve cada comando a Redis, bloquea síncronamente hasta el timeout configurado y mide con precisión de nanosegundos la latencia de la llamada.
  * **Detección de SLA y Auto-Reset**: Si la latencia supera el umbral configurado (`latency-threshold-ms=150`) o la conexión falla de manera consecutiva (`consecutive-delays-threshold=3`), dispara automáticamente un `reset()` del pool de conexiones.
  * **Rotación Segura de Sockets**: El reseteo instancia un nuevo cliente Redis, incrementa `connectionId`, reinicia contadores, respeta un cooldown (`reconnect-cooldown-ms=15000`) para evitar tormentas de reconexión y programa el cierre limpio del socket anterior.
  * **Apagado Limpio (`@PreDestroy`)**: Libera todas las conexiones abiertas cuando el microservicio se detiene.

* **[`RedisHealthCheck.java`](src/main/java/com/company/redis/infrastructure/RedisHealthCheck.java)**:
  * Implementa `org.eclipse.microprofile.health.HealthCheck` (Readiness síncrono).
  * Realiza un `PING` a Redis y reporta diagnósticos incluyendo el `connectionId` activo y si el modo degradado está disponible.

---

## 2. Conclusiones de las Pruebas Realizadas

Se ejecutaron dos niveles de pruebas exhaustivas: **pruebas automatizadas JUnit/QuarkusTest** y **pruebas de caos de red con ToxiProxy**.

### A. Pruebas Automatizadas Unitarias y de Integración (`mvn test`)

Resultados: **7 de 7 pruebas exitosas (100% éxito)**:
1. **Fallback ante Caída Total de Redis (`testSetFallbackWhenRedisDown`)**:
   * *Escenario*: Redis se configuró en un puerto inalcanzable (`localhost:1`).
   * *Resultado*: La petición `POST` no falló con HTTP 500; degradó limpiamente respondiendo HTTP 202 (`DEGRADED_STORED_IN_LOCAL_FALLBACK`). La lectura posterior `GET` recuperó el dato de Caffeine con `isFallback=true`.
2. **Semántica de Clave Inexistente (`testGetNotFoundWhenRedisDown`)**:
   * *Resultado*: Una clave no presente en caché responde HTTP 404 cleanly.
3. **Validación de Payloads (`testPayloadValidation`)**:
   * *Resultado*: Claves en blanco o TTLs menores a 1 son rechazados con HTTP 400.
4. **Seguridad Administrativa (`testUnauthorizedPoolReset` / `testAuthorizedPoolReset`)**:
   * *Resultado*: El reseteo del pool exige la clave `X-Admin-Key`; sin ella responde HTTP 403 `FORBIDDEN`.
5. **Capa de Servicio Aislada (`CacheServiceTest`)**:
   * *Resultado*: Validó la escritura/lectura degradada y la invalidación total de Caffeine (`clearLocalCache`).

### B. Pruebas de Caos e Inyección de Latencia (`test-resilience-no-cache.sh`)

* *Herramienta*: **ToxiProxy** inyectando 300 ms de latencia downstream (superando el SLA de 150 ms).
* *Condición*: Lecturas directas a Redis con `bypassLocalCache=true` (sin auxilio de memoria local).
* *Comportamiento Observado*:
  1. Durante las peticiones afectadas por alta latencia, `RedisPoolManager` detectó que la llamada excedió el umbral SLA (`300ms > 150ms`) incrementando el contador de anomalías.
  2. Al llegar a 3 demoras consecutivas, el microservicio activó el auto-reset proactivo.
  3. El socket y cliente de Redis fue reciclado (`connectionId` cambió de 1 a 2).
  4. Al restaurar la red normal en ToxiProxy, el microservicio recuperó inmediatamente su comunicación normal directa sin reinicio de JVM ni intervención manual.

### Conclusión General

La arquitectura implementada demuestra una **alta resiliencia y tolerancia a fallos**:
* **Continuidad de Servicio**: El sistema nunca entrega un error 500 al cliente cuando Redis sufre demoras o caídas totales; degrada de forma transparente a memoria local acotada.
* **Auto-Recuperación (Self-Healing)**: La aplicación es capaz de detectar sockets zombis o degradados por latencia y sustituirlos en caliente mediante auto-reset.
* **Simplicidad y Eficiencia**: Al eliminar Mutiny y usar programación imperativa directa con Java 21, el código es más fácil de mantener, depurar y perfilar, sin perder robustez en los mecanismos de tolerancia a fallos.
