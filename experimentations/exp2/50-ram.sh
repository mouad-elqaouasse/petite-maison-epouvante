#!/usr/bin/env bash
# Expérience 2, étape 5 — RAM de RabbitMQ, à lancer sur le VPS.
# A. 60 s au repos : memory.current toutes les 0,5 s (3 passages de la sonde rabbitmq-diagnostics, période 20 s).
# B. 20 s avec un appel rabbitmqctl à T+5 s : effet d'une commande CLI sur la RAM du conteneur.
# C. 10 000 messages de 1 Ko en attente (consommateur arrêté), puis purge.
# Échantillonnage dans le conteneur par une seule exec (sh + cat de busybox, négligeables).
set -uo pipefail
k() { kubectl -n sandbox "$@"; }
D=~/exp2/resultats
mkdir -p "$D"

echantillons() {  # $1 durée en s, $2 fichier
  k exec deploy/rabbitmq -- sh -c "i=0; while [ \$i -lt $(($1 * 2)) ]; do echo \$(date +%T) \$((\$(cat /sys/fs/cgroup/memory.current) / 1048576)); sleep 0.5; i=\$((i + 1)); done" > "$2"
}
resume() {  # $1 fichier : min / médiane / max en Mio, et les instants au-dessus de 110 Mio
  awk '{print $2}' "$1" | sort -n | awk '{a[NR]=$1} END{print "min=" a[1], "mediane=" a[int((NR+1)/2)], "max=" a[NR], "(" NR " points)"}'
  echo "au-dessus de 110 Mio : $(awk '$2 > 110 {printf "%s=%s ", $1, $2}' "$1")"
}

echo "--- A. repos 60 s ($(date -u +%T))"
echantillons 60 "$D/ram-repos.txt"
resume "$D/ram-repos.txt"

echo "--- B. un appel rabbitmqctl à T+5 s ($(date -u +%T))"
( sleep 5; echo "rabbitmqctl à $(date -u +%T)"; k exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages > /dev/null ) &
echantillons 20 "$D/ram-cli.txt"
wait
resume "$D/ram-cli.txt"

echo "--- C. 10 000 messages de 1 Ko en attente ($(date -u +%T))"
k scale deploy/exp2-consommateur --replicas=0
k wait --for=delete pod -l app=exp2-consommateur --timeout=60s
echantillons 120 "$D/ram-attente.txt" &
ECH=$!
k exec deploy/exp2-producteur -- python /app/producteur.py --run attente --nb 10000 --taille 1000 | grep BILAN
wait "$ECH"
resume "$D/ram-attente.txt"
echo "dernier point (file pleine, après envoi) : $(tail -1 "$D/ram-attente.txt")"
k exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages message_bytes
k exec deploy/rabbitmq -- rabbitmq-diagnostics -q memory_breakdown --unit mb | head -8
k top pod -l app=rabbitmq
k exec deploy/rabbitmq -- rabbitmqctl -q purge_queue notification.annonces
k exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages
echo "memory.peak : $(($(k exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.peak) / 1048576)) Mio ; oom_kill : $(k exec deploy/rabbitmq -- grep oom_kill /sys/fs/cgroup/memory.events)"
free -h
