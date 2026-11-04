#!/usr/bin/env bash
# SQ-08, étape 4 — redémarre les pods de app un par un (kubectl delete pod, jamais rollout restart : Flux l'annule),
# attend que le nouveau pod soit prêt, surveille la RAM disponible, puis relance la matrice (règles validées sur des
# pods neufs, exp. 1, B9). Arrêt si la RAM disponible passe sous SEUIL Mio, si un pod n'est pas prêt en 7 min, ou si
# un écart reste après un second essai de la matrice 30 s plus tard.
# Usage (sur le VPS, dans ~/sq-08) : bash redemarrer-pods.sh
set -u
SEUIL=300
dispo() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo; }
mkdir -p matrices
for d in keycloak postgresql rabbitmq troc-service notification-service prometheus kube-state-metrics grafana front; do
  ancien=$(kubectl -n app get pods -l "app=$d,!sonde" -o jsonpath='{.items[0].metadata.name}')
  kubectl -n app delete pod "$ancien" --wait=false >/dev/null
  min=$(dispo); debut=$(date +%s)
  while :; do
    m=$(dispo); [ "$m" -lt "$min" ] && min=$m
    if [ "$m" -lt "$SEUIL" ]; then echo "ARRÊT : $m Mio disponibles pendant $d"; kubectl -n app get pods; free -m; exit 2; fi
    if [ $(( $(date +%s) - debut )) -gt 420 ]; then echo "ARRÊT : $d pas prêt après 7 min"; kubectl -n app get pods; exit 3; fi
    pret=$(kubectl -n app get pods -l "app=$d,!sonde" -o jsonpath='{range .items[*]}{.metadata.name}={.status.containerStatuses[0].ready}{"\n"}{end}' \
      | grep -v "^$ancien=" | grep -c "=true$")
    [ "$pret" -ge 1 ] && break
    sleep 2
  done
  echo "$(date -u +%T) $d prêt en $(( $(date +%s) - debut )) s ; minimum disponible : $min Mio"
  sleep 5
  sortie="matrices/matrice-redemarrage-$d.txt"
  if ! bash tester-flux.sh "redémarrage de $d" > "$sortie"; then
    sleep 30
    if ! bash tester-flux.sh "redémarrage de $d, 2e essai" > "$sortie"; then
      cat "$sortie"; echo "ARRÊT : écart après le redémarrage de $d"; exit 1
    fi
  fi
  grep -E "^=== Écarts|ECART|INFO" "$sortie"
done
kubectl -n app get pods
free -m
