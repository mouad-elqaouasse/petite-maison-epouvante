#!/usr/bin/env bash
# Expérience 1, partie 5 — matrice des flux réseau, comparée au résultat attendu à chaque étape.
# À lancer sur le VPS : bash ~/exp1/98-tester-flux.sh <étape>
# Étapes, dans l'ordre d'application des règles :
#   reference (aucune règle) | refus (90) | dns (91) | keycloak-postgres (92) | traefik-keycloak (93)
#   | prometheus (94) | complements (95, puis 96 pour verif-jwt)
# Prérequis : 97-np-intrus.yaml appliqué (pods np-intrus dans sandbox et default).
# Trois types de tests :
#   strict    : nouvelle connexion ; bloquée avant l'étape qui l'ouvre, passante ensuite ;
#   intrus    : passante seulement à l'étape reference ;
#   sensible  : dépend de connexions déjà ouvertes (pool de Keycloak vers PostgreSQL, connexions gardées par Traefik
#               et Prometheus), que le suivi de connexions du noyau peut laisser passer après l'ajout d'un refus :
#               résultat noté [INFO] entre « refus » et l'étape qui l'ouvre, comparé ensuite.
# Code de sortie : nombre d'écarts.
set -uo pipefail

NS=sandbox
ETAPES=(reference refus dns keycloak-postgres traefik-keycloak prometheus complements)
ETAPE=${1:?usage : $0 <étape>}
N=-1
for i in "${!ETAPES[@]}"; do [ "${ETAPES[$i]}" = "$ETAPE" ] && N=$i; done
[ "$N" -ge 0 ] || { echo "étape inconnue : $ETAPE (attendu : ${ETAPES[*]})"; exit 99; }

PG_IP=$(kubectl -n "$NS" get svc postgres -o 'jsonpath={.spec.clusterIP}')
KC_IP=$(kubectl -n "$NS" get svc keycloak -o 'jsonpath={.spec.clusterIP}')
SVC=$NS.svc.cluster.local
ECARTS=0

# tester <nom> <type> <étape d'ouverture> <commande...>
tester() {
  local nom=$1 type=$2 ouvert=${3:-0} attendu obtenu bilan
  shift 3
  # timeout ne lance que des programmes : les fonctions (exportées plus bas) passent par un bash enfant.
  if timeout 20 bash -c '"$@"' _ "$@" >/dev/null 2>&1; then obtenu=PASSE; else obtenu=BLOQUE; fi
  case $type in
    intrus) [ "$N" -eq 0 ] && attendu=PASSE || attendu=BLOQUE ;;
    strict) { [ "$N" -eq 0 ] || [ "$N" -ge "$ouvert" ]; } && attendu=PASSE || attendu=BLOQUE ;;
    sensible) { [ "$N" -eq 0 ] || [ "$N" -ge "$ouvert" ]; } && attendu=PASSE || attendu=? ;;
  esac
  if [ "$attendu" = "?" ]; then bilan=INFO
  elif [ "$attendu" = "$obtenu" ]; then bilan=OK
  else bilan=ECART; ECARTS=$((ECARTS + 1)); fi
  printf '[%-5s] %-48s attendu %-6s obtenu %s\n' "$bilan" "$nom" "$attendu" "$obtenu"
}

dans()   { local ns=$1 cible=$2; shift 2; kubectl -n "$ns" exec "$cible" -- "$@"; }
http_ok() { [ "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$1")" = 200 ]; }
keycloak_pret() { [ "$(kubectl -n "$NS" get pod -l app=keycloak -o 'jsonpath={.items[0].status.containerStatuses[0].ready}')" = true ]; }
up_job() {
  dans "$NS" deploy/prometheus wget -q -O- 'http://localhost:9090/api/v1/query?query=up' \
    | grep -q "\"job\":\"$1\"},\"value\":\[[0-9.]*,\"1\"\]"
}
export NS
export -f dans http_ok keycloak_pret up_job

echo "=== Étape $N : $ETAPE ($(date -u +%T) UTC)"
echo "--- Résolution de noms"
tester "intrus(sandbox) : DNS"                         strict   2 dans "$NS" np-intrus timeout 8 nslookup "postgres.$SVC"
echo "--- PostgreSQL"
tester "keycloak -> postgres:5432 (nouvelle connexion)" strict  3 dans "$NS" deploy/keycloak timeout 5 bash -c "</dev/tcp/postgres.$SVC/5432"
tester "keycloak prêt (sonde, base comprise)"          sensible 3 keycloak_pret
tester "intrus(sandbox) -> postgres par nom"           intrus   0 dans "$NS" np-intrus pg_isready -h "postgres.$SVC" -t 5
tester "intrus(sandbox) -> postgres par IP"            intrus   0 dans "$NS" np-intrus pg_isready -h "$PG_IP" -t 5
tester "intrus(default) -> postgres par nom"           intrus   0 dans default np-intrus pg_isready -h "postgres.$SVC" -t 5
tester "intrus(default) -> postgres par IP"            intrus   0 dans default np-intrus pg_isready -h "$PG_IP" -t 5
echo "--- Keycloak"
tester "intrus(sandbox) -> keycloak:8080 par IP"       intrus   0 dans "$NS" np-intrus wget -q -T 5 -O /dev/null "http://$KC_IP:8080/realms/epouvante-test/.well-known/openid-configuration"
tester "traefik -> keycloak:8080 (nouvelle connexion)" strict   4 dans kube-system deploy/traefik wget -q -T 5 -O /dev/null "http://keycloak.$SVC:8080/realms/epouvante-test/.well-known/openid-configuration"
tester "HTTPS public auth. (découverte OIDC)"          sensible 4 http_ok https://auth.epouvante.menuly-app.com/realms/epouvante-test/.well-known/openid-configuration
echo "--- Prometheus"
tester "prometheus -> keycloak:9000"                   strict   5 dans "$NS" deploy/prometheus wget -q -T 5 -O /dev/null "http://keycloak.$SVC:9000/metrics"
tester "prometheus -> rabbitmq:15692"                  strict   5 dans "$NS" deploy/prometheus wget -q -T 5 -O /dev/null "http://rabbitmq.$SVC:15692/metrics"
tester "prometheus -> API Kubernetes:443"              strict   5 dans "$NS" deploy/prometheus nc -z -w 5 kubernetes.default.svc.cluster.local 443
tester "up{job=keycloak}"                              sensible 5 up_job keycloak
tester "up{job=rabbitmq}"                              sensible 5 up_job rabbitmq
tester "up{job=kubernetes-cadvisor}"                   sensible 5 up_job kubernetes-cadvisor
echo "--- Compléments"
tester "grafana -> prometheus:9090"                    strict   6 dans "$NS" deploy/grafana wget -q -T 5 -O /dev/null "http://prometheus.$SVC:9090/-/ready"
tester "traefik -> whoami:8080"                        strict   6 dans kube-system deploy/traefik wget -q -T 5 -O /dev/null "http://whoami.$SVC/"
tester "HTTPS public epouvante. (whoami)"              sensible 6 http_ok https://epouvante.menuly-app.com/

echo "--- État des pods"
kubectl -n "$NS" get pods -o 'custom-columns=POD:.metadata.name,PRET:.status.containerStatuses[0].ready,REDEMARRAGES:.status.containerStatuses[0].restartCount'
kubectl -n "$NS" get networkpolicy --no-headers 2>/dev/null | awk '{print "règle : " $1}'
echo "=== Écarts : $ECARTS"
exit "$ECARTS"
