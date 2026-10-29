#!/usr/bin/env bash
# Expérience 1, partie 4 — piège « issuer public / JWKS interne ».
# À lancer sur le VPS : bash ~/exp1/73-verifier-jetons.sh
# 1. Sur l'hôte : flux Authorization Code + PKCE complet par l'URL publique (compte auteur-test), et cas négatifs.
# 2. Dans un pod éphémère (step-cli) : clés récupérées par l'adresse interne, jeton vérifié contre l'issuer public.
# Aucun jeton ni mot de passe n'est affiché : seulement les codes HTTP, les erreurs OAuth et les claims décodés.
# Le mot de passe est lu dans le Secret et passé à curl par un fichier temporaire (mode 600), jamais en argument.
set -euo pipefail

NS=sandbox
REALM=epouvante-test
PUBLIC=https://auth.epouvante.menuly-app.com/realms/$REALM
CLIENT=epouvante-front
REDIR=https://epouvante.menuly-app.com/
UTILISATEUR=auteur-test
IMAGE=smallstep/step-cli:0.31.0

TMP=$(mktemp -d)
chmod 700 "$TMP"
trap 'rm -rf "$TMP"' EXIT
( umask 077; kubectl -n "$NS" get secret keycloak-test-users -o 'jsonpath={.data.AUTEUR_PASSWORD}' | base64 -d > "$TMP/mdp" )

ETAT=$(openssl rand -hex 16)
nouveau_verifier() { openssl rand -base64 48 | tr -d '\n=+/' | cut -c1-64; }
challenge_de() { printf '%s' "$1" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '='; }
erreur_oauth() { grep -o '"error":"[^"]*"' "$1" || echo '(pas de champ error)'; }

# Connexion par le formulaire de Keycloak ; renvoie le code d'autorisation. $1 = code_challenge.
obtenir_code() {
  local jar="$TMP/jar.$RANDOM" page action loc
  page=$(curl -sS -c "$jar" -b "$jar" -G "$PUBLIC/protocol/openid-connect/auth" \
    --data-urlencode "client_id=$CLIENT" --data-urlencode response_type=code --data-urlencode scope=openid \
    --data-urlencode "redirect_uri=$REDIR" --data-urlencode "state=$ETAT" \
    --data-urlencode "code_challenge=$1" --data-urlencode code_challenge_method=S256)
  action=$(printf '%s' "$page" | grep -o 'action="[^"]*"' | head -n 1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  [ -n "$action" ] || { echo "ERREUR : formulaire de connexion introuvable" >&2; return 1; }
  loc=$(curl -sS -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' \
    --data-urlencode "username=$UTILISATEUR" --data-urlencode "password@$TMP/mdp" "$action")
  printf '%s' "$loc" | grep -q "state=$ETAT" || { echo "ERREUR : redirection inattendue (state absent)" >&2; return 1; }
  printf '%s' "$loc" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p'
}

# Échange du code contre les jetons. $1 = code, $2 = code_verifier (vide = absent), $3 = fichier de réponse.
echanger() {
  local args=(-d grant_type=authorization_code -d "client_id=$CLIENT" --data-urlencode "code=$1" --data-urlencode "redirect_uri=$REDIR")
  [ -n "$2" ] && args+=(--data-urlencode "code_verifier=$2")
  curl -sS -o "$3" -w '%{http_code}' "${args[@]}" "$PUBLIC/protocol/openid-connect/token"
}

echo "=== A. Demande d'autorisation sans code_challenge (PKCE obligatoire)"
loc=$(curl -sS -o /dev/null -w '%{http_code} %{redirect_url}' -G "$PUBLIC/protocol/openid-connect/auth" \
  --data-urlencode "client_id=$CLIENT" --data-urlencode response_type=code --data-urlencode scope=openid \
  --data-urlencode "redirect_uri=$REDIR" --data-urlencode "state=$ETAT")
echo "HTTP ${loc%% *} ; $(printf '%s' "$loc" | grep -o 'error=[^&]*' || echo 'pas d erreur dans la redirection')"

echo "=== B. Octroi par mot de passe (direct access grants désactivé)"
code=$(curl -sS -o "$TMP/b.json" -w '%{http_code}' -d grant_type=password -d "client_id=$CLIENT" \
  --data-urlencode "username=$UTILISATEUR" --data-urlencode "password@$TMP/mdp" "$PUBLIC/protocol/openid-connect/token")
echo "HTTP $code ; $(erreur_oauth "$TMP/b.json")"

echo "=== C. Échange du code SANS code_verifier"
v=$(nouveau_verifier); c=$(obtenir_code "$(challenge_de "$v")")
echo "HTTP $(echanger "$c" '' "$TMP/c.json") ; $(erreur_oauth "$TMP/c.json")"

echo "=== D. Échange du code avec un MAUVAIS code_verifier"
v=$(nouveau_verifier); c=$(obtenir_code "$(challenge_de "$v")")
echo "HTTP $(echanger "$c" "$(nouveau_verifier)" "$TMP/d.json") ; $(erreur_oauth "$TMP/d.json")"

echo "=== E. Flux PKCE complet (bon code_verifier)"
v=$(nouveau_verifier); c=$(obtenir_code "$(challenge_de "$v")")
echo "HTTP $(echanger "$c" "$v" "$TMP/e.json") ; $(grep -o -E '"(token_type|expires_in|scope)":("[^"]*"|[0-9]+)' "$TMP/e.json" | tr '\n' ' ')"
JETON=$(sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' "$TMP/e.json")
[ -n "$JETON" ] || { echo "ERREUR : pas d'access token" >&2; exit 1; }
rm -f "$TMP/mdp"

echo "=== F. Vérification depuis un pod du cluster (image $IMAGE)"
OVERRIDES='{"spec":{"containers":[{"name":"verif-jwt","image":"'"$IMAGE"'","command":["bash","-s"],"stdin":true,"stdinOnce":true,"tty":false,
"resources":{"requests":{"cpu":"10m","memory":"16Mi"},"limits":{"cpu":"200m","memory":"64Mi"}},
"securityContext":{"runAsNonRoot":true,"runAsUser":1000,"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}'

# Le jeton est transmis par l'entrée standard du pod (pas en argument, pas dans une variable d'environnement du pod).
{ printf 'JETON=%s\n' "$JETON"; cat <<'POD'
set -u
INTERNE=http://keycloak.sandbox.svc:8080/realms/epouvante-test
PUBLIC=https://auth.epouvante.menuly-app.com/realms/epouvante-test
cd /tmp
ecarts=0
verifier() { # $1 libellé, $2 attendu (VALIDE|REFUSE), $3 jeton, $4 issuer, $5 audience
  if printf '%s' "$3" | step crypto jwt verify --jwks jwks.json --iss "$4" --aud "$5" >/dev/null 2>err.txt
  then obtenu=VALIDE; else obtenu="REFUSE ($(head -c 120 err.txt | tr '\n' ' '))"; fi
  case "$obtenu" in "$2"*) bilan=OK ;; *) bilan=ECART; ecarts=$((ecarts + 1)) ;; esac
  echo "[$bilan] $1 : attendu $2, obtenu $obtenu"
}

echo "--- JWKS par l'adresse interne"
# Partie 5 : sous refus total, le premier accès d'un pod neuf peut être refusé le temps que ses règles s'appliquent.
# Nouvel essai chaque seconde, jusqu'à 30 s ; le délai avant le premier succès est affiché.
jwks_ok=0
for essai in $(seq 0 30); do
  curl -sS -f --max-time 2 -o jwks.json "$INTERNE/protocol/openid-connect/certs" 2>/dev/null && { jwks_ok=1; break; }
  sleep 1
done
if [ "$jwks_ok" = 1 ]; then echo "obtenu après $essai s d'attente (essai n° $((essai + 1)))"
else echo "[ECART] toujours refusé après 30 s"; ecarts=$((ecarts + 1)); fi
echo "clés : $(jq -c '[.keys[] | {kid, alg, use}]' jwks.json)"
printf '%s' "$JETON" | step crypto jwt inspect --insecure > jeton.json
echo "kid du jeton : $(jq -r .header.kid jeton.json) ; alg : $(jq -r .header.alg jeton.json)"

echo "--- Claims du jeton (sans signature)"
jq -c '.payload | {iss, aud, azp, typ, preferred_username, roles: .realm_access.roles, duree_s: (.exp - .iat)}' jeton.json

echo "--- Vérifications"
verifier "issuer public + clés internes"          VALIDE "$JETON" "$PUBLIC"  troc-service
verifier "issuer = adresse interne (le piège)"    REFUSE "$JETON" "$INTERNE" troc-service
verifier "audience inconnue"                      REFUSE "$JETON" "$PUBLIC"  service-inconnu
IFS=. read -r h p s <<< "$JETON"
p2=$(jq -c '.payload | .realm_access.roles += ["MODERATEUR"]' jeton.json | step base64 -u -r)
verifier "jeton altéré (rôle MODERATEUR ajouté)" REFUSE "$h.$p2.$s" "$PUBLIC" troc-service

echo "--- Document de découverte lu par l'adresse interne"
curl -sS -f "$INTERNE/.well-known/openid-configuration" | jq -c '{issuer, jwks_uri, token_endpoint}'

echo "--- Accès du pod à l'URL publique (information)"
curl -sS -o /dev/null -w 'HTTP %{http_code} en %{time_total} s\n' --max-time 10 "$PUBLIC/.well-known/openid-configuration" || echo "injoignable"

# Code de sortie du pod non nul s'il y a au moins un écart ; kubectl run le renvoie, et pipefail le propage au script.
echo "=== Écarts : $ecarts"
[ "$ecarts" -eq 0 ]
POD
# Délai de démarrage porté à 3 min (1 min par défaut) : téléchargement de l'image au premier lancement.
} | kubectl -n "$NS" run verif-jwt --rm -i --quiet --restart=Never --pod-running-timeout=3m --image="$IMAGE" --overrides="$OVERRIDES"
