#!/usr/bin/env bash
# Client Keycloak temporaire des campagnes de charge (décision D5 du plan de charge, 24/11/2026).
# À lancer sur le VPS, depuis le poste :
#   ssh ubuntu@51.178.39.218 'bash -s -- creer'     < load/keycloak-client-charge.sh
#   ssh ubuntu@51.178.39.218 'bash -s -- verifier'  < load/keycloak-client-charge.sh
#   ssh ubuntu@51.178.39.218 'bash -s -- supprimer' < load/keycloak-client-charge.sh
#
# creer : client confidentiel charge-jmeter, flux client_credentials seulement (ni navigateur, ni mot de passe),
#   compte de service avec le rôle USER, audience troc-service seulement. Secret du client et sub du compte de service
#   rangés dans le Secret Kubernetes charge-jmeter, jamais affichés.
# supprimer : client (et son compte de service) et Secret charge-jmeter. Lancer donnees-charge.sh nettoyer AVANT :
#   il lit le sub dans ce Secret.
#
# Hors du JSON du realm (exception à l'ADR 0014, validée le 24/11/2026) : le client ne doit pas survivre aux campagnes.
# Relançable sans effet de bord. Connexion de kcadm reprise de deploy/scripts/keycloak-comptes.sh.
set -euo pipefail

ACTION=${1:?usage : creer | verifier | supprimer}
NS=app
REALM=epouvante
CLIENT=charge-jmeter
SECRET=charge-jmeter
KCADM=/opt/keycloak/bin/kcadm.sh
CFG=/tmp/kcadm-charge.config

kc() { kubectl -n "$NS" exec deploy/keycloak -- "$KCADM" "$@" --config "$CFG"; }
kci() { kubectl -n "$NS" exec -i deploy/keycloak -- "$KCADM" "$@" --config "$CFG"; }
secret() { kubectl -n "$NS" get secret "$1" -o "jsonpath={.data.$2}" | base64 -d; echo; }
nettoyer() { kubectl -n "$NS" exec deploy/keycloak -- rm -f "$CFG" || true; }
trap nettoyer EXIT

connexion() {
  kubectl -n "$NS" exec -i deploy/keycloak -- sh -c \
    'read -r MDP && /opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm-charge.config --server http://localhost:8080 --realm master --user "$1" --password "$MDP"' \
    _ "$1"
}

id_client() {
  kc get clients -r "$REALM" -q "clientId=$CLIENT" --fields id --format csv --noquotes | tr -d '\r'
}

ADMIN=$(secret keycloak-admin admin-username)
secret keycloak-admin admin-password | connexion "$ADMIN" >/dev/null
echo "--- kcadm connecté (realm master)"

verifier() {
  local id
  id=$(id_client)
  if [ -z "$id" ]; then
    echo "client $CLIENT : absent"
  else
    kc get "clients/$id" -r "$REALM" \
      --fields "clientId,enabled,publicClient,standardFlowEnabled,implicitFlowEnabled,directAccessGrantsEnabled,serviceAccountsEnabled,protocolMappers(name,config(included.custom.audience))"
    echo "compte de service : rôles = $(kc get "users/$(kc get "clients/$id/service-account-user" -r "$REALM" --fields id --format csv --noquotes | tr -d '\r')/role-mappings/realm" -r "$REALM" --fields name --format csv --noquotes | tr '\r\n' '  ')"
  fi
  if kubectl -n "$NS" get secret "$SECRET" >/dev/null 2>&1; then
    echo "Secret $SECRET : présent, clés = $(kubectl -n "$NS" get secret "$SECRET" -o jsonpath='{.data}' | grep -o '"[a-z-]*":' | tr -d '":' | tr '\n' ' ')"
  else
    echo "Secret $SECRET : absent"
  fi
}

case "$ACTION" in
  creer)
    if [ -z "$(id_client)" ]; then
      kci create clients -r "$REALM" -f - <<'JSON'
{
  "clientId": "charge-jmeter",
  "name": "Campagnes de charge (temporaire)",
  "description": "Supprimé après les campagnes : load/keycloak-client-charge.sh supprimer",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": false,
  "clientAuthenticatorType": "client-secret",
  "standardFlowEnabled": false,
  "implicitFlowEnabled": false,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": true,
  "protocolMappers": [
    {
      "name": "audience-troc-service",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-audience-mapper",
      "config": {
        "included.custom.audience": "troc-service",
        "access.token.claim": "true",
        "id.token.claim": "false",
        "introspection.token.claim": "true"
      }
    }
  ]
}
JSON
      echo "--- client $CLIENT créé"
    else
      echo "--- client $CLIENT déjà présent"
    fi
    ID=$(id_client)
    kc add-roles -r "$REALM" --uusername "service-account-$CLIENT" --rolename USER
    SUB=$(kc get "clients/$ID/service-account-user" -r "$REALM" --fields id --format csv --noquotes | tr -d '\r')
    # Secret du client : passe de kcadm au Secret Kubernetes par un tube, jamais par le terminal.
    kc get "clients/$ID/client-secret" -r "$REALM" --fields value --format csv --noquotes | tr -d '\r\n' \
      | kubectl -n "$NS" create secret generic "$SECRET" --from-file=client-secret=/dev/stdin \
          --from-literal=sub="$SUB" --dry-run=client -o yaml \
      | kubectl apply -f - >/dev/null
    echo "--- Secret $SECRET écrit (client-secret, sub = $SUB)"
    verifier
    ;;
  verifier)
    verifier
    ;;
  supprimer)
    ID=$(id_client)
    if [ -n "$ID" ]; then
      kc delete "clients/$ID" -r "$REALM"
      echo "--- client $CLIENT supprimé (avec son compte de service)"
    fi
    kubectl -n "$NS" delete secret "$SECRET" --ignore-not-found
    verifier
    ;;
  *)
    echo "usage : creer | verifier | supprimer" >&2
    exit 2
    ;;
esac
