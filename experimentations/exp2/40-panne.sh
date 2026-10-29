#!/usr/bin/env bash
# Expérience 2, étape 4 — panne du consommateur pendant l'envoi, à lancer sur le VPS.
# Usage : bash 40-panne.sh [NB=1000] [RUN=panne] [DEBIT=20 msg/s] [PANNE_APRES=15 s]
# 1. relevé toutes les 2 s : RAM du conteneur RabbitMQ et profondeur de la file ;
# 2. envoi de NB messages à DEBIT msg/s (confirmations) ;
# 3. à T+PANNE_APRES, SIGKILL du processus consommateur (messages livrés non acquittés) ; Kubernetes redémarre le conteneur ;
# 4. attente de la vidange de la file, puis bilan à partir des logs des deux vies du conteneur.
# Le relevé n'utilise pas rabbitmqctl : chaque appel lance une seconde VM Erlang dans le conteneur, comptée dans sa RAM
# (premier essai : memory.peak 214 → 270 Mio). On lit memory.current et l'endpoint Prometheus local (wget, busybox).
set -uo pipefail
NS=sandbox
NB=${1:-1000}
RUN=${2:-panne}
DEBIT=${3:-20}
PANNE_APRES=${4:-15}
D=~/exp2/resultats
mkdir -p "$D"
k() { kubectl -n "$NS" "$@"; }

echo "début $(date -u +%T) ; memory.peak RabbitMQ avant : $(($(k exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.peak) / 1048576)) Mio"

(
  while true; do
    m=$(k exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.current)
    q=$(k exec deploy/rabbitmq -- wget -qO- http://localhost:15692/metrics \
      | awk '/^rabbitmq_queue_messages_ready /{r=$2} /^rabbitmq_queue_messages_unacked /{u=$2} END{print "prets="r, "non_acquittes="u}')
    echo "$(date -u +%T) rmq_mio=$((m / 1048576)) $q"
    sleep 2
  done
) > "$D/$RUN-releves.txt" 2>&1 &
RELEVE=$!

k exec deploy/exp2-producteur -- python /app/producteur.py --run "$RUN" --nb "$NB" --debit "$DEBIT" > "$D/$RUN-producteur.log" 2>&1 &
PROD=$!

sleep "$PANNE_APRES"
POD=$(k get pod -l app=exp2-consommateur -o jsonpath='{.items[0].metadata.name}')
echo "panne $(date -u +%T) : SIGKILL du consommateur dans $POD"
# Motif ancré : ne vise que python, pas le « sh -c » parent (PID 1).
k exec "$POD" -- pkill -9 -f '^python /app/consommateur.py'

wait "$PROD"
echo "fin des envois $(date -u +%T)"

for _ in $(seq 60); do
  r=$(k exec deploy/rabbitmq -- wget -qO- http://localhost:15692/metrics \
    | awk '/^rabbitmq_queue_messages_ready /{r=$2} /^rabbitmq_queue_messages_unacked /{u=$2} END{print r + u}')
  [ "$r" = "0" ] && break
  sleep 5
done
kill "$RELEVE"
echo "file vide $(date -u +%T)"

k logs "$POD" --previous > "$D/$RUN-conso-avant.log"
k logs "$POD" > "$D/$RUN-conso-apres.log"
T=$(cat "$D/$RUN-conso-avant.log" "$D/$RUN-conso-apres.log" | grep "TRAITE run=$RUN ")
ids() { echo "$T" | sed -E 's/.* id=([0-9]+) .*/\1/'; }

echo "--- bilan"
grep BILAN "$D/$RUN-producteur.log"
echo "redémarrages du consommateur : $(k get pod "$POD" -o jsonpath='{.status.containerStatuses[0].restartCount}')"
echo "traités avant panne : $(grep -c "TRAITE run=$RUN " "$D/$RUN-conso-avant.log") ; après : $(grep -c "TRAITE run=$RUN " "$D/$RUN-conso-apres.log")"
echo "traitements : $(echo "$T" | wc -l) ; ids distincts : $(ids | sort -un | wc -l) ; doublons : $(ids | sort -n | uniq -d | wc -l)"
echo "ids manquants : $(comm -23 <(seq 1 "$NB" | sort) <(ids | sort -u) | wc -l)"
echo "relivrés (redelivered=True) : $(echo "$T" | grep -c redelivered=True) ; x-delivery-count max : $(echo "$T" | sed -E 's/.*x-delivery-count=([0-9]+).*/\1/' | sort -n | tail -1)"
k exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages
echo "memory.peak RabbitMQ après : $(($(k exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.peak) / 1048576)) Mio"
echo "RAM RabbitMQ pendant le test (relevés) : max $(sed -E 's/.*rmq_mio=([0-9]+).*/\1/' "$D/$RUN-releves.txt" | sort -n | tail -1) Mio"
echo "--- relevés (profondeur de file et RAM), une ligne sur 5"
awk 'NR % 5 == 1' "$D/$RUN-releves.txt"
