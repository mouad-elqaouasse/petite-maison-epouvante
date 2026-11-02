#!/usr/bin/env bash
# SQ-06 — Secrets Keycloak de app, à lancer sur le VPS AVANT le premier démarrage de Keycloak : bash keycloak-secrets.sh
# - keycloak-admin : bootstrap-password (administrateur de démarrage, lu par le pod), admin-username et admin-password
#   (administrateur permanent, créé par keycloak-comptes.sh) ;
# - keycloak-demo-users : un mot de passe par compte de démonstration (clés = noms des comptes du realm epouvante).
# Mots de passe générés ici (openssl rand), jamais affichés. Un Secret existant n'est pas modifié.
set -euo pipefail
NS=app

creer() {
  local nom=$1
  shift
  if kubectl -n "$NS" get secret "$nom" >/dev/null 2>&1; then
    echo "secret/$nom déjà présent : inchangé"
  else
    kubectl -n "$NS" create secret generic "$nom" "$@"
  fi
}

creer keycloak-admin \
  --from-literal=bootstrap-password="$(openssl rand -hex 24)" \
  --from-literal=admin-username=admin-epouvante \
  --from-literal=admin-password="$(openssl rand -hex 24)"

creer keycloak-demo-users \
  --from-literal=membre-demo="$(openssl rand -hex 16)" \
  --from-literal=abonne-demo="$(openssl rand -hex 16)" \
  --from-literal=moderateur-demo="$(openssl rand -hex 16)"

# Contrôle : noms des clés seulement, aucune valeur.
for s in keycloak-admin keycloak-demo-users; do
  echo "$s : $(kubectl -n "$NS" get secret "$s" -o go-template='{{range $k, $v := .data}}{{$k}} {{end}}')"
done
