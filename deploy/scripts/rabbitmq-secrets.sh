#!/usr/bin/env bash
# SQ-05 — Secrets RabbitMQ de app (ADR 0010), à lancer sur le VPS : bash rabbitmq-secrets.sh
# - troc-rabbitmq, notification-rabbitmq : mot de passe en clair de chaque service (clé password) ;
# - rabbitmq-definitions : definitions.json chargé par le broker au démarrage, avec les hachés des mots de passe.
# Mots de passe générés ici (openssl rand), jamais affichés. Relancé, le script garde les mots de passe existants
# et régénère seulement les définitions (à faire quand leur structure change, puis redémarrer le pod RabbitMQ).
set -euo pipefail
NS=app

# Mot de passe du Secret $1 s'il existe, sinon un nouveau.
mot_de_passe() {
  if kubectl -n "$NS" get secret "$1" >/dev/null 2>&1; then
    kubectl -n "$NS" get secret "$1" -o jsonpath='{.data.password}' | base64 -d
  else
    openssl rand -hex 24
  fi
}

# Haché au format rabbit_password_hashing_sha256 : base64(sel de 4 octets + SHA-256(sel + mot de passe)).
hache() {
  openssl rand 4 > "$TMP/sel"
  { cat "$TMP/sel"; printf '%s' "$1"; } | openssl dgst -sha256 -binary > "$TMP/empreinte"
  cat "$TMP/sel" "$TMP/empreinte" | openssl base64 -A
}

TMP=$(mktemp -d)
chmod 700 "$TMP"
trap 'rm -rf "$TMP"' EXIT

MDP_TROC=$(mot_de_passe troc-rabbitmq)
MDP_NOTIFICATION=$(mot_de_passe notification-rabbitmq)
HACHE_TROC=$(hache "$MDP_TROC")
HACHE_NOTIFICATION=$(hache "$MDP_NOTIFICATION")

# Topologie (docs/cadrage.md §1.2.8, reprise de l'exp. 2) : exchange annonces (topic), clé annonce.publiee ;
# file quorum, 1 livraison + 3 réessais (x-delivery-limit=3) puis dead-lettering vers annonces.dlx et la DLQ.
# Droits configure / write / read (§1.2.10) : troc publie sur annonces ; notification lit sa file et la DLQ.
cat > "$TMP/definitions.json" <<EOF
{
  "rabbit_version": "4.4.0",
  "users": [
    {"name": "troc", "password_hash": "$HACHE_TROC", "hashing_algorithm": "rabbit_password_hashing_sha256", "tags": []},
    {"name": "notification", "password_hash": "$HACHE_NOTIFICATION", "hashing_algorithm": "rabbit_password_hashing_sha256", "tags": []}
  ],
  "vhosts": [{"name": "/"}],
  "permissions": [
    {"user": "troc", "vhost": "/", "configure": "^$", "write": "^annonces$", "read": "^$"},
    {"user": "notification", "vhost": "/", "configure": "^$", "write": "^$", "read": "^notification\\\\.annonce-publiee(\\\\.dlq)?$"}
  ],
  "exchanges": [
    {"name": "annonces", "vhost": "/", "type": "topic", "durable": true, "auto_delete": false, "internal": false, "arguments": {}},
    {"name": "annonces.dlx", "vhost": "/", "type": "fanout", "durable": true, "auto_delete": false, "internal": false, "arguments": {}}
  ],
  "queues": [
    {"name": "notification.annonce-publiee", "vhost": "/", "durable": true, "auto_delete": false, "arguments": {
      "x-queue-type": "quorum", "x-delivery-limit": 3, "x-dead-letter-exchange": "annonces.dlx",
      "x-dead-letter-strategy": "at-least-once", "x-overflow": "reject-publish"}},
    {"name": "notification.annonce-publiee.dlq", "vhost": "/", "durable": true, "auto_delete": false, "arguments": {
      "x-queue-type": "quorum"}}
  ],
  "bindings": [
    {"source": "annonces", "vhost": "/", "destination": "notification.annonce-publiee", "destination_type": "queue",
     "routing_key": "annonce.publiee", "arguments": {}},
    {"source": "annonces.dlx", "vhost": "/", "destination": "notification.annonce-publiee.dlq", "destination_type": "queue",
     "routing_key": "", "arguments": {}}
  ]
}
EOF

# Mots de passe passés par fichier, jamais en argument (visibles dans ps) ni affichés.
appliquer() {  # $1 Secret, $2 mot de passe
  printf '%s' "$2" > "$TMP/password"
  kubectl -n "$NS" create secret generic "$1" --from-file=password="$TMP/password" --dry-run=client -o yaml \
    | kubectl apply -f -
}
appliquer troc-rabbitmq "$MDP_TROC"
appliquer notification-rabbitmq "$MDP_NOTIFICATION"
kubectl -n "$NS" create secret generic rabbitmq-definitions --from-file=definitions.json="$TMP/definitions.json" \
  --dry-run=client -o yaml | kubectl apply -f -
