#!/usr/bin/env bash
# SQ-13 — Vérifie que les messages en attente survivent à la suppression du pod RabbitMQ (docs/cadrage.md §1.2.8),
# à lancer sur le VPS, Flux suspendu (sinon il remet notification-service à 1 replica pendant le test).
# Prérequis : image exp2-amqp:2 (clients pika, experimentations/exp2/app) importée dans containerd de K3s, puis
# supprimée après le test (docker save | ssh … 'sudo k3s ctr images import -').
# Pods éphémères, mots de passe lus dans les Secrets par variables d'environnement, jamais affichés.
# Déroulé : notification-service à 0 replica ; troc publie N messages persistants confirmés ; kubectl delete pod
# RabbitMQ ; mêmes comptes après le redémarrage ; notification lit et acquitte ; notification-service remis à 1.
# Attendu : N messages prêts avant et après la suppression du pod (code 0) ; sinon code 1 (écart).
# Arrêt (code 2) si la RAM disponible passe sous SEUIL Mio.
set -u
NS=app
N=${N:-10}
SEUIL=300
FILE=notification.annonce-publiee
ecarts=0

dispo() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo; }
surveiller() {
  local m; m=$(dispo)
  if [ "$m" -lt "$SEUIL" ]; then echo "ARRÊT : $m Mio disponibles ($1)"; kubectl -n "$NS" get pods; free -m; exit 2; fi
}
remettre() { kubectl -n "$NS" scale deploy/notification-service --replicas=1 >/dev/null; }
trap remettre EXIT

pod_rabbitmq() { kubectl -n "$NS" get pods -l app=rabbitmq -o jsonpath='{.items[0].metadata.name}'; }
uid_rabbitmq() { kubectl -n "$NS" get pods -l app=rabbitmq -o jsonpath='{.items[0].metadata.uid}'; }
# Messages prêts et non acquittés de la file, et prêts de la DLQ (rabbitmqctl dans le pod : même cookie Erlang).
files() {
  kubectl -n "$NS" exec "$(pod_rabbitmq)" -- rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers
}
prets() { files | awk -v f="$1" '$1 == f {print $2}'; }

# $1 : nom du pod et mode (publier, verifier, consommer).
# Label app=troc-service + sonde (comme les sondes de SQ-08) : seul label autorisé vers RabbitMQ:5672 par les
# NetworkPolicies ; sonde de disponibilité toujours en échec, donc jamais dans les endpoints du Service troc-service.
client() {
  local nom="rabbitmq-persistance-$1"
  kubectl -n "$NS" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $nom
  labels:
    app: troc-service
    sonde: "true"
spec:
  terminationGracePeriodSeconds: 0
  restartPolicy: Never
  automountServiceAccountToken: false
  enableServiceLinks: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: client
      image: exp2-amqp:2
      imagePullPolicy: Never
      env:
        - name: MODE
          value: "$1"
        - name: NOMBRE
          value: "$N"
        - name: MDP_TROC
          valueFrom:
            secretKeyRef:
              name: troc-rabbitmq
              key: password
        - name: MDP_NOTIFICATION
          valueFrom:
            secretKeyRef:
              name: notification-rabbitmq
              key: password
      command:
        - python
        - -c
        - |
          import os, sys, pika
          from commun import connexion
          FILE, ECHANGE, CLE = "notification.annonce-publiee", "annonces", "annonce.publiee"
          mode, n = os.environ["MODE"], int(os.environ["NOMBRE"])
          if mode == "publier":
              troc = connexion("troc", os.environ["MDP_TROC"])
              ch = troc.channel(); ch.confirm_delivery()
              for i in range(1, n + 1):
                  ch.basic_publish(ECHANGE, CLE, f'{{"verification":"SQ-13","numero":{i}}}'.encode(),
                                   pika.BasicProperties(delivery_mode=2), mandatory=True)
              print(f"OK : troc publie {n} messages persistants (confirmés, routés)", flush=True)
              troc.close()
          elif mode == "verifier":
              for compte, cle in (("troc", "MDP_TROC"), ("notification", "MDP_NOTIFICATION")):
                  connexion(compte, os.environ[cle]).close()
                  print(f"OK : compte {compte} accepté", flush=True)
          else:
              notif = connexion("notification", os.environ["MDP_NOTIFICATION"])
              ch, lus = notif.channel(), 0
              while True:
                  methode, _, _ = ch.basic_get(FILE)
                  if methode is None:
                      break
                  ch.basic_ack(methode.delivery_tag); lus += 1
              print(f"OK : notification lit et acquitte {lus} messages", flush=True)
              notif.close()
      resources:
        requests:
          cpu: 20m
          memory: 32Mi
        limits:
          cpu: 200m
          memory: 64Mi
      readinessProbe:
        exec:
          command: ["false"]
        periodSeconds: 60
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
EOF
  kubectl -n "$NS" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$nom" --timeout=90s >/dev/null \
    || { echo "ECART : pod $nom pas terminé"; ecarts=$((ecarts + 1)); }
  kubectl -n "$NS" logs "pod/$nom" | grep -v "^connexion impossible"
  kubectl -n "$NS" delete pod "$nom" --wait=false >/dev/null
}

echo "=== SQ-13 — persistance des messages RabbitMQ ($(date -u +'%F %T') UTC)"
kubectl -n "$NS" get pods -l app=rabbitmq -o wide
kubectl -n "$NS" get pvc 2>/dev/null
echo "Nom du nœud Erlang : $(kubectl -n "$NS" exec "$(pod_rabbitmq)" -- rabbitmqctl -q eval 'node().')"
echo "RAM disponible : $(dispo) Mio"
surveiller "début"

echo; echo "--- 1. notification-service arrêté (0 replica)"
kubectl -n "$NS" scale deploy/notification-service --replicas=0
kubectl -n "$NS" wait --for=delete pod -l app=notification-service --timeout=60s >/dev/null 2>&1
depart=$(prets "$FILE")
if [ "$depart" != "0" ]; then echo "ARRÊT : $depart messages déjà en attente, test non significatif"; exit 1; fi

echo; echo "--- 2. troc publie $N messages"
client publier
avant=$(prets "$FILE")
files
echo "Messages prêts avant la suppression du pod : $avant (attendu : $N)"
[ "$avant" = "$N" ] || { echo "ECART : $avant au lieu de $N avant la suppression"; ecarts=$((ecarts + 1)); }

echo; echo "--- 3. kubectl delete pod $(pod_rabbitmq)"
ancien=$(uid_rabbitmq); debut=$(date +%s); min=$(dispo)
kubectl -n "$NS" delete pod -l app=rabbitmq --wait=false
while :; do
  surveiller "redémarrage de RabbitMQ"; m=$(dispo); [ "$m" -lt "$min" ] && min=$m
  pret=$(kubectl -n "$NS" get pods -l app=rabbitmq \
    -o jsonpath='{range .items[*]}{.metadata.uid}={.status.containerStatuses[0].ready}{"\n"}{end}' 2>/dev/null \
    | grep -v "^$ancien=" | grep -c "=true$")
  [ "$pret" -ge 1 ] && [ "$(kubectl -n "$NS" get pods -l app=rabbitmq --no-headers | wc -l)" -eq 1 ] && break
  if [ $(( $(date +%s) - debut )) -gt 300 ]; then echo "ARRÊT : RabbitMQ pas prêt après 5 min"; kubectl -n "$NS" get pods; exit 1; fi
  sleep 2
done
echo "Nouveau pod $(pod_rabbitmq) prêt en $(( $(date +%s) - debut )) s ; minimum disponible : $min Mio"
echo "Nom du nœud Erlang : $(kubectl -n "$NS" exec "$(pod_rabbitmq)" -- rabbitmqctl -q eval 'node().')"

echo; echo "--- 4. après la suppression du pod"
apres=$(prets "$FILE")
files
echo "Messages prêts après la suppression du pod : ${apres:-file absente} (attendu : $N)"
[ "$apres" = "$N" ] || { echo "ECART : ${apres:-file absente} au lieu de $N après la suppression"; ecarts=$((ecarts + 1)); }
client verifier

echo; echo "--- 5. nettoyage"
client consommer
remettre
kubectl -n "$NS" rollout status deploy/notification-service --timeout=180s
files
echo "RAM disponible : $(dispo) Mio"

echo; echo "=== Écarts : $ecarts"
[ "$ecarts" -eq 0 ]
