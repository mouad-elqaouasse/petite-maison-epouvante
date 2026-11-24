#!/usr/bin/env bash
# Captures Grafana juste après une campagne (historique de Prometheus en emptyDir : à faire avant toute recréation
# du pod). Ouvre le tunnel SSH vers Grafana (non exposé, SQ-07), lance les captures, puis ferme le tunnel.
# Aucun mot de passe : lecture anonyme en Viewer derrière le tunnel (ADR 0028).
# Usage (Git Bash ou WSL2) : load/captures-grafana.sh <campagne> <début ISO> <fin ISO>
#   exemple : load/captures-grafana.sh c0 2026-11-24T15:13:00Z 2026-11-24T15:35:00Z
set -euo pipefail

CAMPAGNE=${1:?usage : captures-grafana.sh <campagne> <début ISO> <fin ISO>}
DEBUT=${2:?début ISO}
FIN=${3:?fin ISO}
VPS=ubuntu@51.178.39.218
PORT=13000

cd "$(dirname "$0")/.."

# Tunnel : port local 13000 → port-forward sur le VPS → Service grafana. -tt : à la fermeture de la session SSH, le
# port-forward distant reçoit SIGHUP et s'arrête.
ssh -tt -o BatchMode=yes -o ExitOnForwardFailure=yes -L "$PORT:localhost:$PORT" "$VPS" \
  "kubectl -n app port-forward svc/grafana $PORT:3000" > /dev/null 2>&1 < /dev/null &
TUNNEL=$!
fermer() {
  kill "$TUNNEL" 2>/dev/null || true
  wait "$TUNNEL" 2>/dev/null || true
  # Motif « [p]ort-forward » : la commande de contrôle ne se compte pas elle-même.
  restants=$(ssh -o BatchMode=yes "$VPS" 'ps -eo args | grep -c "[p]ort-forward svc/grafana" || true')
  echo "tunnel fermé (port-forward restants sur le VPS : $restants)"
}
trap fermer EXIT

for _ in $(seq 30); do
  curl -sf "http://localhost:$PORT/api/health" > /dev/null 2>&1 && break
  sleep 1
done
curl -sf "http://localhost:$PORT/api/health" > /dev/null || { echo "ERREUR : Grafana injoignable par le tunnel" >&2; exit 1; }
echo "tunnel ouvert : http://localhost:$PORT"

node load/captures-grafana.mjs "$CAMPAGNE" "$DEBUT" "$FIN" "$PORT"
