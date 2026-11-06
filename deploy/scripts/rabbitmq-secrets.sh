#!/usr/bin/env bash
# SQ-05 — Secrets RabbitMQ de app (ADR 0010, 0026), à lancer sur le VPS : bash rabbitmq-secrets.sh
# (rabbitmq-definitions.json copié dans le même dossier).
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

# Comptes, droits et topologie (docs/cadrage.md §1.2.8 et §1.2.10) : rabbitmq-definitions.json, à côté de ce script,
# aussi chargé par les tests de notification-service (ADR 0026). Seuls les hachés sont ajoutés ici
# (base64, sans « | » : séparateur de sed sans risque).
sed -e "s|__HACHE_TROC__|$HACHE_TROC|" -e "s|__HACHE_NOTIFICATION__|$HACHE_NOTIFICATION|" \
  "$(dirname "$0")/rabbitmq-definitions.json" > "$TMP/definitions.json"
if grep -q '__HACHE_' "$TMP/definitions.json"; then
  echo "Marqueur de haché non remplacé" >&2
  exit 1
fi

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
