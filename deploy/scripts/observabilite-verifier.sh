#!/usr/bin/env bash
# SQ-07 — Vérifie l'observabilité de app, à lancer sur le VPS : ssh ubuntu@51.178.39.218 'bash -s' < observabilite-verifier.sh
#  1. chaque cible attendue est UP dans Prometheus, et aucune cible n'est DOWN ;
#  2. les tableaux de bord « Santé » et « Montée en charge » sont provisionnés dans Grafana ;
#  3. /actuator/prometheus reste inaccessible depuis Internet (ADR 0007).
# Le mot de passe Grafana n'est jamais affiché : il est lu dans l'environnement du pod Grafana, pas sur le VPS.
# Code de sortie non nul au premier écart compté.
set -uo pipefail
NS=app
HOTE=https://epouvante.menuly-app.com
JOBS="prometheus kubernetes-cadvisor kube-state-metrics troc-service notification-service keycloak rabbitmq rabbitmq-files"
TABLEAUX="sante montee-en-charge"
ecarts=0

ok() { echo "OK : $*"; }
ecart() { echo "ECART : $*"; ecarts=$((ecarts + 1)); }

requete() {
  kubectl -n "$NS" exec deploy/prometheus -- wget -qO- "http://localhost:9090/api/v1/query?query=$1" 2>/dev/null
}

# 1. Cibles
cibles=$(requete 'up')
if [ -z "$cibles" ]; then
  ecart "Prometheus ne répond pas"
else
  for job in $JOBS; do
    n=$(echo "$cibles" | jq --arg j "$job" '[.data.result[] | select(.metric.job == $j and .value[1] == "1")] | length')
    if [ "$n" -ge 1 ]; then ok "$job : $n cible(s) UP"; else ecart "$job : aucune cible UP"; fi
  done
  echo "$cibles" | jq -r '.data.result[] | select(.value[1] != "1") | "ECART : cible DOWN \(.metric.job) \(.metric.instance)"' \
    | while read -r ligne; do echo "$ligne"; done
  bas=$(echo "$cibles" | jq '[.data.result[] | select(.value[1] != "1")] | length')
  ecarts=$((ecarts + bas))
fi

# 2. Tableaux de bord (API de Grafana, compte administrateur lu dans le pod)
for uid in $TABLEAUX; do
  code=$(kubectl -n "$NS" exec deploy/grafana -- sh -c \
    'wget -qS -O /dev/null "http://admin:${GF_SECURITY_ADMIN_PASSWORD}@localhost:3000/api/dashboards/uid/'"$uid"'" 2>&1 | awk "/HTTP\// {print \$2; exit}"' 2>/dev/null)
  if [ "$code" = "200" ]; then ok "tableau de bord $uid provisionné"; else ecart "tableau de bord $uid absent (code ${code:-aucun})"; fi
done

# 3. Métriques jamais exposées par l'Ingress. Le code HTTP ne suffit pas : sous /, le front renvoie index.html (200)
# pour toute adresse inconnue (routage Angular). On cherche donc le format de Prometheus dans la réponse.
for chemin in /actuator/prometheus /api/troc/actuator/prometheus /api/notifications/actuator/prometheus; do
  reponse=$(curl -s -w '\n%{http_code}' "$HOTE$chemin")
  code=${reponse##*$'\n'}
  if echo "$reponse" | grep -q '^# TYPE '; then ecart "$chemin : métriques lisibles depuis Internet ($code)"
  else ok "$chemin depuis Internet : $code, aucune métrique"; fi
done

echo "Écarts : $ecarts"
[ "$ecarts" -eq 0 ]
