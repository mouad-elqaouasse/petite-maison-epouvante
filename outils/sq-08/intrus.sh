#!/usr/bin/env bash
# SQ-08 — test du pod « intrus » (intrus-front.yaml), lancé depuis le poste.
# Usage : bash outils/sq-08/intrus.sh <phase>     (phase : avant | apres)
# Crée le pod dans app, lance les tests, écrit docs/soutenance/captures/sq-08/<phase>.txt, puis supprime le pod.
set -euo pipefail

PHASE="${1:?usage : intrus.sh avant|apres}"
VPS="ubuntu@51.178.39.218"
ICI="$(cd "$(dirname "$0")" && pwd)"
SORTIE="$ICI/../../docs/soutenance/captures/sq-08/$PHASE.txt"
mkdir -p "$(dirname "$SORTIE")"

# Même réglage de service links que le Deployment front (absent = true, valeur par défaut de Kubernetes).
LIENS="$(ssh "$VPS" "kubectl -n app get deploy front -o jsonpath='{.spec.template.spec.enableServiceLinks}'")"
LIENS="${LIENS:-true}"

sed "s/ENABLE_SERVICE_LINKS/$LIENS/" "$ICI/intrus-front.yaml" | ssh "$VPS" 'kubectl apply -f -'
ssh "$VPS" "kubectl -n app wait pod/intrus-front --for=jsonpath='{.status.phase}'=Running --timeout=120s"

# Tests lancés dans le pod ; chaque commande est précédée de son libellé, avec son code de sortie.
TESTS='
t() { echo "\$ $*"; "$@" 2>&1; echo "-> code $?"; echo; }
t id
t env
t ls /var/run/secrets/kubernetes.io/serviceaccount
t nslookup postgresql.app.svc.cluster.local
for cible in postgresql:5432 rabbitmq:5672 keycloak:8080 troc-service:8081 prometheus:9090; do
  t nc -zv -w 3 "${cible%:*}" "${cible#*:}"
done
t wget -T 3 -O /dev/null http://example.com
'

{
  echo "# SQ-08 — pod intrus-front (label app: front), phase : $PHASE"
  echo "# Date (UTC) : $(date -u '+%Y-%m-%d %H:%M:%S')"
  echo "# enableServiceLinks du pod : $LIENS"
  echo "# NetworkPolicies de app :"
  ssh "$VPS" 'kubectl -n app get networkpolicy 2>&1' | sed 's/^/#   /'
  echo
  # Code de sortie = celui du dernier test : ignoré, les codes de chaque test sont dans la capture.
  ssh "$VPS" "kubectl -n app exec intrus-front -- sh -c '$TESTS'" || true
} > "$SORTIE"

ssh "$VPS" 'kubectl -n app delete pod intrus-front --wait=true'
echo "Capture écrite : $SORTIE"
