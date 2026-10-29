#!/usr/bin/env bash
# Expérience 1, partie 5 — mesure du HPA : temps de montée (1 → 3 replicas), RAM à 3 replicas, temps de descente.
# À lancer sur le VPS, après 80-hpa-demo.yaml : bash ~/exp1/81-mesurer-hpa.sh
# Relevés toutes les 10 s dans un CSV (~/exp1/hpa-<date>.csv) et sur la sortie standard.
# Seuil d'arrêt : RAM disponible (MemAvailable) < 500 Mio → charge, HPA supprimés et Deployment ramené à 1 replica.
# Le pod de charge est supprimé à la sortie du script, quelle qu'en soit la cause (fin, erreur, coupure SSH).
set -euo pipefail

NS=sandbox
SEUIL_MIO=500
PERIODE=10
MAX_MONTEE=600      # s : au-delà, la montée est considérée en échec
TENUE_A_3=120       # s : maintien de la charge une fois les 3 replicas prêts
MAX_DESCENTE=720    # s : fenêtre de stabilisation de 300 s + marge
SORTIE=$HOME/exp1/hpa-$(date -u +%Y%m%dT%H%M%SZ).csv

nettoyer() { kubectl -n "$NS" delete pod charge-cpu --now --ignore-not-found >/dev/null 2>&1 || true; }
trap nettoyer EXIT

dispo_mio() { awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo; }

arret_urgence() {
  echo "ARRET : RAM disponible $1 Mio < $SEUIL_MIO Mio" | tee -a "$SORTIE"
  nettoyer
  kubectl -n "$NS" delete hpa cpu-demo --ignore-not-found
  kubectl -n "$NS" scale deploy/cpu-demo --replicas=1
  exit 2
}

# Un relevé : heure, secondes depuis T0, phase, replicas voulus par le HPA, replicas du Deployment, replicas prêts,
# utilisation CPU moyenne vue par le HPA (% de la request), RAM disponible du nœud.
releve() {
  local hpa deploy dispo
  hpa=$(kubectl -n "$NS" get hpa cpu-demo -o 'jsonpath={.status.desiredReplicas},{.status.currentMetrics[0].resource.current.averageUtilization}')
  deploy=$(kubectl -n "$NS" get deploy cpu-demo -o 'jsonpath={.spec.replicas},{.status.readyReplicas}')
  dispo=$(dispo_mio)
  IFS=, read -r H_VOULUS CPU <<< "$hpa"
  IFS=, read -r D_VOULUS D_PRETS <<< "$deploy"
  D_PRETS=${D_PRETS:-0}
  echo "$(date -u +%T),$(( $(date +%s) - T0 )),$1,$H_VOULUS,$D_VOULUS,$D_PRETS,${CPU:-},$dispo" | tee -a "$SORTIE"
  [ "$dispo" -ge "$SEUIL_MIO" ] || arret_urgence "$dispo"
}

etat_detaille() {
  echo "--- $1 ($(date -u +%T))"
  kubectl -n "$NS" top pods
  kubectl top nodes
  free -h
}

echo "--- Vérifications préalables"
kubectl -n "$NS" rollout status deploy/cpu-demo --timeout=120s
for i in $(seq 1 12); do
  cible=$(kubectl -n "$NS" get hpa cpu-demo -o 'jsonpath={.status.currentMetrics[0].resource.current.averageUtilization}')
  [ -n "$cible" ] && break
  sleep 10
done
[ -n "$cible" ] || { echo "HPA sans métrique CPU (cible <unknown>) : arrêt"; exit 1; }
[ "$(kubectl -n "$NS" get deploy cpu-demo -o 'jsonpath={.status.readyReplicas}')" = 1 ] || { echo "Le Deployment n'est pas à 1 replica prêt : arrêt"; exit 1; }
[ "$(dispo_mio)" -ge "$SEUIL_MIO" ] || { echo "RAM disponible déjà sous le seuil : arrêt"; exit 1; }
etat_detaille "Avant la charge"

echo "heure,t_s,phase,hpa_voulus,deploy_replicas,prets,cpu_pct,ram_dispo_mio" | tee "$SORTIE"

echo "--- Démarrage de la charge"
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: charge-cpu
  namespace: sandbox
  labels:
    app: charge-cpu
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 65534
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: charge
      image: busybox:1.37.0
      # Requêtes enchaînées sans pause, comme dans le walkthrough de la documentation Kubernetes.
      command: ["/bin/sh", "-c", "while true; do wget -q -O /dev/null http://cpu-demo.sandbox.svc.cluster.local; done"]
      resources:
        requests:
          cpu: 50m
          memory: 8Mi
        limits:
          cpu: 200m
          memory: 16Mi
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
EOF
kubectl -n "$NS" wait --for=condition=Ready pod/charge-cpu --timeout=120s
T0=$(date +%s)
echo "T0 (charge active) : $(date -u +%T)"

T_DECISION=; T_3PRETS=
while :; do
  releve montee
  [ -z "$T_DECISION" ] && [ "${H_VOULUS:-1}" -gt 1 ] && T_DECISION=$(( $(date +%s) - T0 ))
  [ "$D_PRETS" -ge 3 ] && { T_3PRETS=$(( $(date +%s) - T0 )); break; }
  [ $(( $(date +%s) - T0 )) -lt "$MAX_MONTEE" ] || { echo "ECHEC : 3 replicas non prêts après $MAX_MONTEE s"; break; }
  sleep "$PERIODE"
done

if [ -n "$T_3PRETS" ]; then
  FIN_TENUE=$(( $(date +%s) + TENUE_A_3 ))
  while [ "$(date +%s)" -lt "$FIN_TENUE" ]; do sleep "$PERIODE"; releve tenue; done
  etat_detaille "RAM à 3 replicas (charge maintenue depuis $TENUE_A_3 s)"
  echo "--- Horodatage exact des pods (création, passage à prêt)"
  kubectl -n "$NS" get pods -l app=cpu-demo -o 'jsonpath={range .items[*]}{.metadata.name} {.metadata.creationTimestamp} {.status.conditions[?(@.type=="Ready")].lastTransitionTime}{"\n"}{end}'
fi

echo "--- Arrêt de la charge"
nettoyer
T_ARRET=$(date +%s)
echo "Charge arrêtée : $(date -u +%T)"

T_DESCENTE=
while :; do
  sleep "$PERIODE"
  releve descente
  [ "$D_VOULUS" = 1 ] && [ "$D_PRETS" = 1 ] && [ "$(kubectl -n "$NS" get pods -l app=cpu-demo --no-headers | wc -l)" = 1 ] \
    && { T_DESCENTE=$(( $(date +%s) - T_ARRET )); break; }
  [ $(( $(date +%s) - T_ARRET )) -lt "$MAX_DESCENTE" ] || { echo "ECHEC : pas revenu à 1 replica après $MAX_DESCENTE s"; break; }
done
etat_detaille "Après la descente"

echo "--- Événements du HPA"
kubectl -n "$NS" get events --field-selector involvedObject.kind=HorizontalPodAutoscaler,involvedObject.name=cpu-demo \
  -o 'jsonpath={range .items[*]}{.lastTimestamp} {.reason} {.message}{"\n"}{end}'

echo "--- Synthèse"
echo "T0 = début de la charge ; précision des relevés : $PERIODE s (horodatages exacts ci-dessus)"
echo "Première décision du HPA (> 1 replica) : ${T_DECISION:-non observée} s"
echo "3 replicas prêts : ${T_3PRETS:-non atteint} s"
echo "Retour à 1 replica après l'arrêt de la charge : ${T_DESCENTE:-non atteint} s"
echo "CSV : $SORTIE"
