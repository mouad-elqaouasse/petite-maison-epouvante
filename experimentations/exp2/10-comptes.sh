#!/usr/bin/env bash
# Expérience 2 — comptes RabbitMQ au moindre privilège (docs/cadrage.md §1.2.10), à lancer sur le VPS.
# Mots de passe générés ici, passés à rabbitmqctl par l'entrée standard et rangés dans des Secrets ; jamais affichés.
# Droits (configure / write / read) sur le vhost « / » :
#   exp2-producteur   : rien / exchange annonces / rien
#   exp2-consommateur : rien / rien / ses deux files (notification.annonces et sa DLQ)
set -euo pipefail
NS=sandbox

rmq() { kubectl -n "$NS" exec -i deploy/rabbitmq -- rabbitmqctl -q "$@"; }

creer() {  # $1 compte, $2 secret, $3 configure, $4 write, $5 read
  local mdp
  mdp=$(openssl rand -hex 24)
  printf '%s' "$mdp" | rmq add_user "$1"
  rmq set_permissions -p / "$1" "$3" "$4" "$5"
  kubectl -n "$NS" create secret generic "$2" \
    --from-literal=RABBITMQ_UTILISATEUR="$1" --from-literal=RABBITMQ_MOT_DE_PASSE="$mdp"
}

creer exp2-producteur exp2-producteur '^$' '^annonces$' '^$'
creer exp2-consommateur exp2-consommateur '^$' '^$' '^notification\.annonces(\.dlq)?$'
rmq list_permissions -p /
