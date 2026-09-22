# Arquitectura y Conexión a Redis — redis-resilient-initial-ms

Este documento describe la arquitectura de conexión a Redis y resiliencia implementada en este microservicio Quarkus mediante **programación imperativa síncrona (sin Mutiny)**.

---

## 1. Tipo de Conexión y Modelo

El microservicio utiliza el cliente **`quarkus-redis-client`** bajo programación **imperativa y síncrona** (`io.vertx.redis.client.Redis` y `io.vertx.redis.client.RedisAPI` core, sin SmallRye Mutiny).

El ciclo de vida del cliente está administrado por [`RedisPoolManager`](src/main/java/com/company/redis/infrastructure/RedisPoolManager.java) de forma imperativa:
1. **Reseteo proactivo del pool**: rotación de sockets y renovación de conexiones en caliente ante degradación sostenida de latencia o fallos de red.
2. **Medición síncrona de latencia SLA**: detección imperativa de retrasos que excedan el umbral configurado.
3. **Cierre limpio (`@PreDestroy`)**: liberación ordenada de sockets en el apagado del servicio.

---

## 2. Dependencias Maven (`pom.xml`)

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-redis-client</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

---

## 3. Configuración (`application.properties`)

```properties
# Conexión Redis / ToxiProxy
quarkus.redis.hosts=redis://localhost:6380
quarkus.redis.max-pool-size=20
quarkus.redis.max-pool-waiting=32
quarkus.redis.timeout=2s
quarkus.redis.reconnect-attempts=3
quarkus.redis.reconnect-interval=500ms
# Si requiere contraseña:
# quarkus.redis.password=mi-password

# Umbrales de Resiliencia del Microservicio
app.redis.resilience.latency-threshold-ms=150
app.redis.resilience.consecutive-delays-threshold=3
app.redis.resilience.reconnect-cooldown-ms=15000
app.redis.resilience.admin-secret=${APP_REDIS_RESILIENCE_ADMIN_SECRET:supersecret123}

# Evitar duplicar el health check con el check custom
quarkus.redis.health.enabled=false
```

---

## 4. Arquitectura de Resiliencia (Imperativa)

1. **`CacheResource`**: Endpoints JAX-RS síncronos imperativos que retornan `jakarta.ws.rs.core.Response`.
2. **`CacheService`**: Capa de servicio síncrona con `@CircuitBreaker` y `@Fallback` imperativos, orquestando Redis y memoria local (**Caffeine** con expiración dinámica por entrada).
3. **`RedisPoolManager`**: Encapsula el cliente Vert.x Core `Redis` y `RedisAPI`, ejecutando llamadas con timeout y midiendo latencia imperativamente con `executeTimed()`.
4. **`RedisHealthCheck`**: Implementación síncrona de `org.eclipse.microprofile.health.HealthCheck` (Readiness check estándar).
