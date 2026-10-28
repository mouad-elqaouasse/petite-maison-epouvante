#!/usr/bin/env bash
# Expérience 1, partie 4 — création du realm de test et des mots de passe des comptes de test.
# À lancer sur le VPS : bash ~/exp1/72-configurer-realm.sh
# Prérequis : Keycloak prêt dans sandbox ; Secret keycloak-test-users (AUTEUR_PASSWORD, ABONNE_PASSWORD).
# Aucun mot de passe n'est affiché ni passé en argument d'une commande lancée sur le VPS :
# - le mot de passe admin est lu dans l'environnement du pod Keycloak (variable développée dans le pod) ;
# - les mots de passe de test passent par l'entrée standard de kcadm.sh (reset-password -f -).
set -euo pipefail

NS=sandbox
REALM=epouvante-test
DIR=$(cd "$(dirname "$0")" && pwd)
KCADM=/opt/keycloak/bin/kcadm.sh
CFG=/tmp/kcadm.config

kc() { kubectl -n "$NS" exec -i deploy/keycloak -- "$KCADM" "$@" --config "$CFG"; }
nettoyer() { kubectl -n "$NS" exec deploy/keycloak -- rm -f "$CFG" || true; }
trap nettoyer EXIT

echo "--- Connexion de kcadm (realm master, depuis le pod)"
kubectl -n "$NS" exec deploy/keycloak -- sh -c \
  '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config --server http://localhost:8080 --realm master --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"'

echo "--- Création du realm $REALM"
if kc get "realms/$REALM" --fields realm >/dev/null 2>&1; then
  echo "Realm déjà présent : création ignorée"
else
  kc create realms -f - < "$DIR/71-realm-epouvante-test.json"
fi

for compte in auteur-test:AUTEUR_PASSWORD abonne-test:ABONNE_PASSWORD; do
  utilisateur=${compte%%:*}
  cle=${compte#*:}
  echo "--- Mot de passe de $utilisateur (valeur lue dans le Secret, non affichée)"
  id=$(kc get users -r "$REALM" -q "username=$utilisateur" -q exact=true --fields id --format csv --noquotes | tr -d '\r')
  [ -n "$id" ] || { echo "ERREUR : utilisateur $utilisateur introuvable" >&2; exit 1; }
  mdp=$(kubectl -n "$NS" get secret keycloak-test-users -o "jsonpath={.data.$cle}" | base64 -d)
  printf '{"type":"password","value":"%s","temporary":false}' "$mdp" \
    | kc update "users/$id/reset-password" -r "$REALM" -n -f -
  unset mdp
done

echo "--- Vérification (aucune valeur secrète)"
kc get "realms/$REALM" --fields realm,enabled,sslRequired,accessTokenLifespan,revokeRefreshToken,refreshTokenMaxReuse,bruteForceProtected,registrationAllowed
kc get clients -r "$REALM" -q clientId=epouvante-front \
  --fields "clientId,publicClient,standardFlowEnabled,implicitFlowEnabled,directAccessGrantsEnabled,redirectUris,attributes(pkce.code.challenge.method),protocolMappers(name,config(included.custom.audience,access.token.claim))"
for utilisateur in auteur-test abonne-test; do
  id=$(kc get users -r "$REALM" -q "username=$utilisateur" -q exact=true --fields id --format csv --noquotes | tr -d '\r')
  echo "$utilisateur : rôles du realm = $(kc get "users/$id/role-mappings/realm" -r "$REALM" --fields name --format csv --noquotes | tr '\r\n' '  ')"
  echo "$utilisateur : identifiants = $(kc get "users/$id/credentials" -r "$REALM" --fields type --format csv --noquotes | tr '\r\n' '  ')"
done

# Console d'administration par tunnel : KC_HOSTNAME_ADMIN ne suffit pas, la console se connecte au realm master
# par son URL de frontend (par défaut KC_HOSTNAME, où /realms/master n'est pas routé → 404 sur 3p-cookies).
# En dernier : après ce changement, l'issuer de master change et le jeton de kcadm déjà obtenu est refusé (401).
echo "--- Realm master : URL de frontend = adresse du tunnel"
kc update realms/master -s attributes.frontendUrl=http://localhost:8180
