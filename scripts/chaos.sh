#!/usr/bin/env bash
# Ayudante para pruebas de caos contra Redis a través de ToxiProxy.
# Requiere: docker-compose (o podman) levantado con `docker compose up -d`
# y curl. API de ToxiProxy en http://localhost:8474
#
# Uso:
#   ./scripts/chaos.sh latency [ms] [jitter_ms]   # agrega latencia (downstream), default 300/50
#   ./scripts/chaos.sh timeout                    # las conexiones se cuelgan (no responden, no cierran)
#   ./scripts/chaos.sh disconnect                 # corte total: conexiones nuevas son rechazadas
#   ./scripts/chaos.sh reset                       # elimina todos los toxics y reactiva el proxy
#   ./scripts/chaos.sh status                      # muestra el estado actual del proxy

set -euo pipefail

TOXIPROXY_API="${TOXIPROXY_API:-http://localhost:8474}"
PROXY="redis"

cmd="${1:-status}"

case "$cmd" in
  latency)
    latency="${2:-300}"
    jitter="${3:-50}"
    curl -s -X POST "${TOXIPROXY_API}/proxies/${PROXY}/toxics" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"latency_down\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":${latency},\"jitter\":${jitter}}}"
    echo
    echo "Latencia de ${latency}ms (+/- ${jitter}ms) inyectada. Umbral de la app: quarkus.redis-resilience.latency-threshold-ms=150"
    ;;

  timeout)
    curl -s -X POST "${TOXIPROXY_API}/proxies/${PROXY}/toxics" \
      -H "Content-Type: application/json" \
      -d '{"name":"hang","type":"timeout","attributes":{"timeout":0}}'
    echo
    echo "Las conexiones ahora se cuelgan indefinidamente (simula hanging connections)."
    ;;

  disconnect)
    curl -s -X POST "${TOXIPROXY_API}/proxies/${PROXY}" \
      -H "Content-Type: application/json" \
      -d '{"enabled":false}'
    echo
    echo "Proxy deshabilitado: nuevas conexiones seran rechazadas (Connection refused)."
    echo "Usa './scripts/chaos.sh reset' para restaurar la conexion."
    ;;

  reset)
    curl -s -X POST "${TOXIPROXY_API}/proxies/${PROXY}" \
      -H "Content-Type: application/json" \
      -d '{"enabled":true}' > /dev/null
    for t in $(curl -s "${TOXIPROXY_API}/proxies/${PROXY}/toxics" | grep -o '"name":"[^"]*"' | cut -d'"' -f4); do
      curl -s -X DELETE "${TOXIPROXY_API}/proxies/${PROXY}/toxics/${t}" > /dev/null
      echo "Toxic eliminado: ${t}"
    done
    echo "Proxy reactivado y sin toxics."
    ;;

  status)
    curl -s "${TOXIPROXY_API}/proxies/${PROXY}"
    echo
    ;;

  *)
    echo "Uso: $0 {latency [ms] [jitter_ms]|timeout|disconnect|reset|status}"
    exit 1
    ;;
esac
