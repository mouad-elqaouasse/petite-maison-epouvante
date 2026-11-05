#!/usr/bin/env bash
# SQ-08 — matrice de flux de app (adaptée de experimentations/exp1/98-tester-flux.sh), lancée sur le VPS.
# Usage : bash tester-flux.sh <étape>        (prérequis : sondes.yaml appliqué, sondes en Running)
#
# L'attendu est calculé à partir des règles présentes dans app (label regle des NetworkPolicies) :
#   - aucune NetworkPolicy : tout passe ;
#   - sinon un flux passe seulement si toutes les règles qu'il demande sont présentes ("jamais" : flux interdit).
# Types de tests :
#   strict   : connexion neuve depuis une sonde, comparée à l'attendu ;
#   sensible : chemin réel (HTTPS public, cibles Prometheus, pods prêts). Des connexions ouvertes avant une règle
#              peuvent encore servir (exp. 1, B2) : si l'attendu est BLOQUE, le résultat est seulement affiché (INFO).
# Chaque test est un programme (kubectl, curl, bash -c), jamais une fonction : timeout ne lance pas de fonction
# (exp. 1, B1). Code de sortie : nombre d'écarts.
set -u

ETAPE="${1:?usage : tester-flux.sh <étape>}"
NOEUD=51.178.39.218
DOMAINE=epouvante.menuly-app.com
K=(kubectl -n app exec)

NB=$(kubectl -n app get networkpolicy --no-headers 2>/dev/null | wc -l)
REGLES=" $(kubectl -n app get networkpolicy -o custom-columns=R:.metadata.labels.regle --no-headers 2>/dev/null | sort -u | tr '\n' ' ') "
ECARTS=0

attendu() {
  [ "$NB" -eq 0 ] && { echo PASSE; return; }
  local r
  for r in "$@"; do
    case "$REGLES" in *" $r "*) ;; *) echo BLOQUE; return ;; esac
  done
  echo PASSE
}

# verifier <strict|sensible> <libellé> "<règles nécessaires>" <commande...>
verifier() {
  local type=$1 libelle=$2 besoin=$3 att obt statut
  shift 3
  # shellcheck disable=SC2086
  att=$(attendu $besoin)
  if timeout 15 "$@" >/dev/null 2>&1; then obt=PASSE; else obt=BLOQUE; fi
  if [ "$type" = sensible ] && [ "$att" = BLOQUE ]; then
    statut=INFO
  elif [ "$att" = "$obt" ]; then
    statut=OK
  else
    statut=ECART; ECARTS=$((ECARTS + 1))
  fi
  printf '[%-5s] %-8s %-52s attendu %-6s obtenu %s\n' "$statut" "$type" "$libelle" "$att" "$obt"
}

echo "=== Matrice SQ-08 — étape : $ETAPE — $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
echo "=== $NB NetworkPolicies ; règles :$REGLES"

# DNS
verifier strict "front (intrus) -> DNS"                      "sortie-dns"                         "${K[@]}" sonde-front -- nslookup postgresql.app.svc.cluster.local

# Flux autorisés
verifier strict "troc-service -> postgresql:5432"            "sortie-dns services-postgresql"     "${K[@]}" sonde-troc -- nc -z -w 3 postgresql 5432
verifier strict "troc-service -> rabbitmq:5672"              "sortie-dns services-rabbitmq"       "${K[@]}" sonde-troc -- nc -z -w 3 rabbitmq 5672
verifier strict "troc-service -> keycloak:8080 (JWKS)"       "sortie-dns services-keycloak"       "${K[@]}" sonde-troc -- nc -z -w 3 keycloak 8080
verifier strict "notification -> postgresql:5432"            "sortie-dns services-postgresql"     "${K[@]}" sonde-notification -- nc -z -w 3 postgresql 5432
verifier strict "notification -> rabbitmq:5672"              "sortie-dns services-rabbitmq"       "${K[@]}" sonde-notification -- nc -z -w 3 rabbitmq 5672
verifier strict "notification -> keycloak:8080 (JWKS)"       "sortie-dns services-keycloak"       "${K[@]}" sonde-notification -- nc -z -w 3 keycloak 8080
verifier strict "keycloak -> postgresql:5432"                "sortie-dns services-postgresql"     "${K[@]}" sonde-keycloak -- nc -z -w 3 postgresql 5432
verifier strict "prometheus -> troc-service:8081"            "sortie-dns prometheus-cibles"       "${K[@]}" sonde-prometheus -- nc -z -w 3 troc-service 8081
verifier strict "prometheus -> notification-service:8081"    "sortie-dns prometheus-cibles"       "${K[@]}" sonde-prometheus -- nc -z -w 3 notification-service 8081
verifier strict "prometheus -> rabbitmq:15692"               "sortie-dns prometheus-cibles"       "${K[@]}" sonde-prometheus -- nc -z -w 3 rabbitmq 15692
verifier strict "prometheus -> keycloak:9000"                "sortie-dns prometheus-cibles"       "${K[@]}" sonde-prometheus -- nc -z -w 3 keycloak 9000
verifier strict "prometheus -> kube-state-metrics:8080"      "sortie-dns prometheus-cibles"       "${K[@]}" sonde-prometheus -- nc -z -w 3 kube-state-metrics 8080
verifier strict "prometheus -> kubelet $NOEUD:10250"         "prometheus-cibles"                  "${K[@]}" sonde-prometheus -- nc -z -w 3 "$NOEUD" 10250
verifier strict "prometheus -> API $NOEUD:6443"              "sortie-api-k8s"                     "${K[@]}" sonde-prometheus -- nc -z -w 3 "$NOEUD" 6443
verifier strict "kube-state-metrics -> API $NOEUD:6443"      "sortie-api-k8s"                     "${K[@]}" sonde-ksm -- nc -z -w 3 "$NOEUD" 6443
verifier strict "grafana -> prometheus:9090"                 "sortie-dns grafana-prometheus"      "${K[@]}" sonde-grafana -- nc -z -w 3 prometheus 9090

# Flux interdits
verifier strict "front (intrus) -> postgresql:5432"          "jamais"                             "${K[@]}" sonde-front -- nc -z -w 3 postgresql 5432
verifier strict "front (intrus) -> rabbitmq:5672"            "jamais"                             "${K[@]}" sonde-front -- nc -z -w 3 rabbitmq 5672
verifier strict "front (intrus) -> keycloak:8080"            "jamais"                             "${K[@]}" sonde-front -- nc -z -w 3 keycloak 8080
verifier strict "front (intrus) -> troc-service:8081"        "jamais"                             "${K[@]}" sonde-front -- nc -z -w 3 troc-service 8081
verifier strict "front (intrus) -> prometheus:9090"          "jamais"                             "${K[@]}" sonde-front -- nc -z -w 3 prometheus 9090
verifier strict "front (intrus) -> Internet"                 "jamais"                             "${K[@]}" sonde-front -- wget -q -T 3 -O /dev/null http://example.com
# US1a : troc-service → Cloudinary en 443 seulement (troc-sortie-https) ; ni HTTP, ni le nœud, ni les autres pods.
verifier strict "troc-service -> api.cloudinary.com:443"     "sortie-dns troc-sortie-https"       "${K[@]}" sonde-troc -- nc -z -w 3 api.cloudinary.com 443
verifier strict "troc-service -> Internet en HTTP (80)"      "jamais"                             "${K[@]}" sonde-troc -- wget -q -T 3 -O /dev/null http://example.com
verifier strict "troc-service -> nœud $NOEUD:443"           "jamais"                             "${K[@]}" sonde-troc -- nc -z -w 3 "$NOEUD" 443
verifier strict "troc-service -> notification-service:8081" "jamais"                             "${K[@]}" sonde-troc -- nc -z -w 3 notification-service 8081
verifier strict "notification -> api.cloudinary.com:443"     "jamais"                             "${K[@]}" sonde-notification -- nc -z -w 3 api.cloudinary.com 443
verifier strict "front (intrus) -> api.cloudinary.com:443"   "jamais"                             "${K[@]}" sonde-front -- nc -z -w 3 api.cloudinary.com 443
verifier strict "grafana -> Internet"                        "jamais"                             "${K[@]}" sonde-grafana -- wget -q -T 3 -O /dev/null http://example.com
verifier strict "keycloak -> rabbitmq:5672"                  "jamais"                             "${K[@]}" sonde-keycloak -- nc -z -w 3 rabbitmq 5672
verifier strict "prometheus -> postgresql:5432"              "jamais"                             "${K[@]}" sonde-prometheus -- nc -z -w 3 postgresql 5432
verifier strict "default (intrus) -> postgresql:5432"        "jamais"                             kubectl -n default exec sonde-default -- nc -z -w 3 postgresql.app.svc.cluster.local 5432
verifier strict "default (intrus) -> keycloak:8080"          "jamais"                             kubectl -n default exec sonde-default -- nc -z -w 3 keycloak.app.svc.cluster.local 8080

# Chemins réels
verifier sensible "HTTPS public : page d'accueil"            "traefik-entree"                     curl -sf -o /dev/null --max-time 10 "https://$DOMAINE/"
verifier sensible "HTTPS public : /api/troc/version"         "traefik-entree"                     curl -sf -o /dev/null --max-time 10 "https://$DOMAINE/api/troc/version"
verifier sensible "HTTPS public : /api/notifications/version" "traefik-entree"                    curl -sf -o /dev/null --max-time 10 "https://$DOMAINE/api/notifications/version"
verifier sensible "HTTPS public : découverte OIDC auth."     "traefik-entree services-postgresql" curl -sf -o /dev/null --max-time 10 "https://auth.$DOMAINE/realms/epouvante/.well-known/openid-configuration"
verifier sensible "Prometheus : toutes les cibles up"        "sortie-dns prometheus-cibles sortie-api-k8s" \
  bash -c 'kubectl -n app exec deploy/prometheus -- wget -qO- "http://localhost:9090/api/v1/query?query=min(up)" | grep -q "\"1\"\]"'
verifier sensible "Pods de app prêts (hors sondes)"          "sortie-dns services-postgresql services-rabbitmq services-keycloak" \
  bash -c '! kubectl -n app get pods -l "!sonde,!sq-08" -o jsonpath="{.items[*].status.containerStatuses[*].ready}" | grep -q false'

echo "=== Écarts : $ECARTS"
exit "$ECARTS"
