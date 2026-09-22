#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:8085/api/v1/cache"
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHAOS_SH="${SCRIPTS_DIR}/chaos.sh"

echo "========================================================"
echo " [TEST] Validación de Reseteo de Conexión Redis (Quarkus)"
echo "        Condición: SIN uso de caché local (bypass=true)"
echo "========================================================"
echo ""

# 0. Asegurar proxy limpio
"$CHAOS_SH" reset > /dev/null 2>&1 || true

# Esperar 16s si hubo un cooldown previo para garantizar que el reset no esté bloqueado
sleep 15

# 1. Limpiar caché local de la app
echo "1. Limpiando caché local en el microservicio..."
curl -s -X POST "${BASE_URL}/local-cache/clear" | jq .

# 2. Consultar ID de conexión inicial
echo ""
echo "2. Obteniendo ID de conexión inicial desde Redis..."
INITIAL_CONN_JSON=$(curl -s "${BASE_URL}/connection/id")
echo "$INITIAL_CONN_JSON" | jq .
INITIAL_ID=$(echo "$INITIAL_CONN_JSON" | jq -r .redisClientId)

# 3. Guardar clave de prueba en Redis
echo ""
echo "3. Guardando clave de prueba 'user:session:99' en Redis..."
curl -s -X POST "${BASE_URL}" \
  -H "Content-Type: application/json" \
  -d '{"key":"user:session:99","value":"data_session_active","ttlSeconds":300}' | jq .

# 4. Limpiar nuevamente la memoria local para garantizar que NO quede nada en Caffeine
curl -s -X POST "${BASE_URL}/local-cache/clear" > /dev/null

# 5. Lectura normal con bypassLocalCache=true
echo ""
echo "4. Leyendo clave con bypassLocalCache=true (directo a Redis)..."
curl -s "${BASE_URL}/user:session:99?bypassLocalCache=true" | jq .

# 6. Inyectar caos de latencia (300ms > timeout de 250ms y umbral SLA de 150ms)
echo ""
echo "5. Inyectando latencia de 300ms con ToxiProxy..."
"$CHAOS_SH" latency 300 10 > /dev/null

# 7. Ejecutar peticiones afectadas
echo ""
echo "6. Enviando peticiones durante la degradación (bypassLocalCache=true)..."
for i in {1..3}; do
  START=$(date +%s%N)
  RESP=$(curl -s -w "\n%{http_code}" "${BASE_URL}/user:session:99?bypassLocalCache=true")
  END=$(date +%s%N)
  ELAPSED_MS=$(( (END - START) / 1000000 ))
  HTTP_CODE=$(echo "$RESP" | tail -n1)
  BODY=$(echo "$RESP" | head -n1)
  echo "   Intento $i: HTTP $HTTP_CODE | Latencia: ${ELAPSED_MS}ms | Respuesta: $BODY"
done

# 8. Restaurar la red en ToxiProxy
echo ""
echo "7. Restaurando la red (eliminando toxic de latencia)..."
"$CHAOS_SH" reset > /dev/null
echo "Esperando 5s para que el Circuit Breaker transicione de OPEN a HALF-OPEN/CLOSED..."
sleep 5

# 9. Consultar nuevo ID de conexión
echo ""
echo "8. Verificando nuevo ID de conexión tras el reseteo proactivo..."
NEW_CONN_JSON=$(curl -s "${BASE_URL}/connection/id")
echo "$NEW_CONN_JSON" | jq .
NEW_ID=$(echo "$NEW_CONN_JSON" | jq -r .redisClientId)

# 10. Validar recuperación directa en Redis con bypassLocalCache=true
echo ""
echo "9. Leyendo nuevamente de Redis con bypassLocalCache=true..."
RECOVERED_RESP=$(curl -s "${BASE_URL}/user:session:99?bypassLocalCache=true")
echo "$RECOVERED_RESP" | jq .

echo ""
echo "========================================================"
echo " RESUMEN DE LA VALIDACIÓN"
echo "========================================================"
echo " ID Conexión Inicial   : $INITIAL_ID"
echo " ID Conexión Post-Reset: $NEW_ID"
if [ "$NEW_ID" -ne "$INITIAL_ID" ]; then
  echo " RESULTADO: ¡EXITOSO! Se renovó el socket/conexión oficial de Quarkus."
else
  echo " RESULTADO: FALLO - El ID de conexión no cambió."
fi
echo "========================================================"
