#!/usr/bin/env bash
# SQ-06 — Comptes Keycloak de app (adapté de experimentations/exp1/72-configurer-realm.sh), à lancer sur le VPS une
# fois Keycloak prêt et le realm epouvante importé : bash keycloak-comptes.sh. Relançable sans effet de bord.
#  1. administrateur permanent (realm master, rôle admin) ;
#  2. mots de passe des comptes de démonstration du realm epouvante ;
#  3. suppression de l'administrateur de démarrage (bandeau « temporary admin » de la console) ;
#  4. en dernier, URL de frontend du realm master = adresse du tunnel (console d'administration, ADR 0003).
# Prérequis : Secrets keycloak-admin et keycloak-demo-users (keycloak-secrets.sh).
# Aucun mot de passe n'est affiché ni passé en argument d'une commande lancée sur le VPS : les valeurs passent par
# l'entrée standard des commandes exécutées dans le pod.
set -euo pipefail

NS=app
REALM=epouvante
KCADM=/opt/keycloak/bin/kcadm.sh
CFG=/tmp/kcadm.config
TUNNEL=http://localhost:8180

kc() { kubectl -n "$NS" exec -i deploy/keycloak -- "$KCADM" "$@" --config "$CFG"; }
secret() { kubectl -n "$NS" get secret "$1" -o "jsonpath={.data.$2}" | base64 -d; }
nettoyer() { kubectl -n "$NS" exec deploy/keycloak -- rm -f "$CFG" || true; }
trap nettoyer EXIT

ADMIN=$(secret keycloak-admin admin-username)

# Connexion de kcadm par l'adresse interne du pod, mot de passe lu sur l'entrée standard.
connexion() {
  kubectl -n "$NS" exec -i deploy/keycloak -- sh -c \
    'read -r MDP && /opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config --server http://localhost:8080 --realm master --user "$1" --password "$MDP"' \
    _ "$1"
}

id_utilisateur() {
  kc get users -r "$1" -q "username=$2" -q exact=true --fields id --format csv --noquotes | tr -d '\r'
}

# $1 realm, $2 utilisateur ; mot de passe lu sur l'entrée standard de la fonction.
definir_mot_de_passe() {
  local id mdp
  # Lu avant tout appel à kc : kubectl exec -i consommerait l'entrée standard.
  read -r mdp
  id=$(id_utilisateur "$1" "$2")
  [ -n "$id" ] || { echo "ERREUR : utilisateur $2 introuvable dans $1" >&2; exit 1; }
  printf '{"type":"password","value":"%s","temporary":false}' "$mdp" \
    | kc update "users/$id/reset-password" -r "$1" -n -f -
}

echo "--- Connexion de kcadm (realm master)"
if secret keycloak-admin admin-password | connexion "$ADMIN" >/dev/null 2>&1; then
  echo "connecté avec l'administrateur permanent $ADMIN"
else
  secret keycloak-admin bootstrap-password | connexion admin-demarrage >/dev/null
  echo "connecté avec l'administrateur de démarrage"
fi

echo "--- 1. Administrateur permanent $ADMIN"
if [ -z "$(id_utilisateur master "$ADMIN")" ]; then
  kc create users -r master -s "username=$ADMIN" -s enabled=true
fi
secret keycloak-admin admin-password | definir_mot_de_passe master "$ADMIN"
kc add-roles -r master --uusername "$ADMIN" --rolename admin

echo "--- 2. Comptes de démonstration (valeurs lues dans keycloak-demo-users, non affichées)"
for utilisateur in membre-demo abonne-demo moderateur-demo; do
  secret keycloak-demo-users "$utilisateur" | definir_mot_de_passe "$REALM" "$utilisateur"
done

echo "--- 3. Suppression de l'administrateur de démarrage"
secret keycloak-admin admin-password | connexion "$ADMIN" >/dev/null
id=$(id_utilisateur master admin-demarrage)
if [ -n "$id" ]; then
  kc delete "users/$id" -r master
  echo "admin-demarrage supprimé"
else
  echo "admin-demarrage déjà absent"
fi

echo "--- Vérification (aucune valeur secrète)"
kc get "realms/$REALM" --fields realm,enabled,sslRequired,accessTokenLifespan,revokeRefreshToken,refreshTokenMaxReuse,bruteForceProtected,registrationAllowed
kc get clients -r "$REALM" -q clientId=epouvante-front \
  --fields "clientId,publicClient,standardFlowEnabled,implicitFlowEnabled,directAccessGrantsEnabled,redirectUris,attributes(pkce.code.challenge.method),protocolMappers(name,config(included.custom.audience))"
for utilisateur in membre-demo abonne-demo moderateur-demo; do
  id=$(id_utilisateur "$REALM" "$utilisateur")
  echo "$utilisateur : rôles = $(kc get "users/$id/role-mappings/realm" -r "$REALM" --fields name --format csv --noquotes | tr '\r\n' '  ')"
  echo "$utilisateur : identifiants = $(kc get "users/$id/credentials" -r "$REALM" --fields type --format csv --noquotes | tr '\r\n' '  ')"
done
echo "master : utilisateurs = $(kc get users -r master --fields username --format csv --noquotes | tr '\r\n' '  ')"

# En dernier : après ce changement, l'issuer de master change et le jeton de kcadm déjà obtenu est refusé (401,
# exp. 1, partie 4, étape 7). KC_HOSTNAME_ADMIN ne suffit pas : la console se connecte au realm master par son URL
# de frontend, publique par défaut et non routée.
echo "--- 4. Realm master : URL de frontend = $TUNNEL"
kc update realms/master -s "attributes.frontendUrl=$TUNNEL"
