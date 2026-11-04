#!/usr/bin/env bash
# SQ-08 — applique les NetworkPolicies une étape à la fois (fichiers du répertoire courant), matrice après chaque étape.
# Écart : la matrice est relancée 30 s plus tard (collecte Prometheus) ; s'il reste un écart, les règles de l'étape
# sont retirées aussitôt, et toutes les règles le sont si la page publique ne répond plus. Puis arrêt.
# Usage (sur le VPS, dans ~/sq-08) : bash appliquer-regles.sh
set -u
ETAPES=(
  "01 00-refus-total.yaml 01-sortie-dns.yaml"
  "02 02-traefik-entree.yaml"
  "03 03-services-postgresql.yaml"
  "04 04-services-rabbitmq.yaml"
  "05 05-services-keycloak.yaml"
  "06 06-prometheus-cibles.yaml"
  "07 07-sortie-api-k8s.yaml"
  "08 08-grafana-prometheus.yaml"
)
mkdir -p matrices
for e in "${ETAPES[@]}"; do
  read -r num fichiers <<< "$e"
  args=(); for f in $fichiers; do args+=(-f "$f"); done
  kubectl apply "${args[@]}"
  sleep 10
  sortie="matrices/matrice-$num-${fichiers%%.yaml*}.txt"
  if ! bash tester-flux.sh "$num ($fichiers)" > "$sortie"; then
    sleep 30
    if ! bash tester-flux.sh "$num ($fichiers), 2e essai" > "$sortie"; then
      cat "$sortie"
      echo "ÉCART à l'étape $num : retrait de $fichiers"
      kubectl delete "${args[@]}"
      if ! curl -sf -o /dev/null --max-time 10 https://epouvante.menuly-app.com/; then
        echo "Page publique en panne : retrait de toutes les règles"
        kubectl -n app delete networkpolicy --all
      fi
      exit 1
    fi
  fi
  grep -E "^===|ECART|INFO" "$sortie"
done
free -m
