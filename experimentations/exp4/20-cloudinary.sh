#!/bin/sh
# Expérience 4 — Cloudinary depuis un pod (image curlimages/curl, sh de busybox).
# Identifiants lus dans l'environnement (Secret cloudinary-exp4). Toute la sortie passe par masquer() :
# cloud_name, api_key et api_secret n'apparaissent jamais dans les logs.
# Cas : N nominal (upload), V vérification et suppression, E1 mauvais identifiants, E2 fichiers > 2 Mo, F état final.

CN="$CLOUDINARY_CLOUD_NAME"
AK="$CLOUDINARY_API_KEY"
AS="$CLOUDINARY_API_SECRET"
API="https://api.cloudinary.com/v1_1/$CN"
DOSSIER="epouvante-test"
REP=/tmp/reponse.json

masquer() {
  sed -e "s|$AS|<masqué>|g" -e "s|$AK|<masqué>|g" -e "s|$CN|<masqué>|g"
}

# champ NOM : valeur d'un champ JSON simple (chaîne ou nombre) dans $REP
champ() {
  sed -e 's|\\/|/|g' "$REP" | sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -1
}

# signer "params triés" secret : SHA-1 hexadécimal de params + secret (signature Cloudinary)
signer() {
  printf '%s%s' "$1" "$2" | sha1sum | cut -d' ' -f1
}

# upload FICHIER PUBLIC_ID CLE SECRET : affiche "code temps"
upload() {
  ts=$(date +%s)
  sig=$(signer "folder=$DOSSIER&public_id=$2&timestamp=$ts" "$4")
  curl -sS --max-time 120 -o "$REP" -w '%{http_code} %{time_total}s' \
    -F "file=@$1" -F "api_key=$3" -F "timestamp=$ts" -F "folder=$DOSSIER" \
    -F "public_id=$2" -F "signature=$sig" "$API/image/upload"
}

# supprimer PUBLIC_ID : destroy signé, cache du CDN invalidé
supprimer() {
  ts=$(date +%s)
  sig=$(signer "invalidate=true&public_id=$1&timestamp=$ts" "$AS")
  curl -sS --max-time 30 -o "$REP" -w '%{http_code}' \
    -F "public_id=$1" -F "invalidate=true" -F "api_key=$AK" -F "timestamp=$ts" \
    -F "signature=$sig" "$API/image/destroy"
}

# admin CHEMIN : GET sur l'Admin API, identifiants passés par stdin (absents des arguments du processus)
admin() {
  printf 'user = "%s:%s"\n' "$AK" "$AS" | curl -sS --max-time 30 -K - -o "$REP" -w '%{http_code}' "$API/$1"
}

# bmp FICHIER COTE : BMP 24 bits COTExCOTE, pixels aléatoires (COTE multiple de 4, pas de bourrage)
le32() {
  n=$1
  printf "\\$(printf %03o $((n & 255)))\\$(printf %03o $(((n >> 8) & 255)))\\$(printf %03o $(((n >> 16) & 255)))\\$(printf %03o $(((n >> 24) & 255)))"
}
bmp() {
  donnees=$(($2 * $2 * 3))
  {
    printf 'BM'; le32 $((54 + donnees)); le32 0; le32 54
    le32 40; le32 "$2"; le32 "$2"; printf '\001\000\030\000'; le32 0; le32 "$donnees"
    le32 2835; le32 2835; le32 0; le32 0
    head -c "$donnees" /dev/urandom
  } > "$1"
}

principal() {
  echo "=== $(date -u '+%F %T') UTC — curl $(curl --version | head -1 | cut -d' ' -f2)"

  echo "--- N : upload nominal ($(wc -c < /photos/photo-test.jpg) octets, JPEG)"
  r=$(upload /photos/photo-test.jpg "exp4-nominal" "$AK" "$AS")
  PID=$(champ public_id); URL=$(champ secure_url)
  echo "HTTP $r ; public_id=$PID ; bytes=$(champ bytes) ; format=$(champ format) ; asset_folder=$(champ asset_folder)"
  echo "URL=$URL"

  echo "--- V : accès public, suppression, vérification"
  echo "GET URL : $(curl -sS --max-time 30 -o /dev/null -w '%{http_code} %{content_type} %{size_download} octets' "$URL")"
  echo "destroy : HTTP $(supprimer "$PID") ; result=$(champ result)"
  for i in 1 2 3 4 5 6; do
    c=$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' "$URL")
    echo "GET URL après suppression, essai $i : $c"
    [ "$c" = "404" ] && break
    sleep 10
  done
  echo "Admin API resources/image/upload/$PID : HTTP $(admin "resources/image/upload/$PID") ; $(champ message)"

  echo "--- E1a : bonne api_key, mauvais api_secret"
  echo "HTTP $(upload /photos/photo-test.jpg "exp4-e1a" "$AK" "faux-secret-exp4") ; message=$(champ message)"
  echo "--- E1b : api_key inventée"
  echo "HTTP $(upload /photos/photo-test.jpg "exp4-e1b" "000000000000000" "$AS") ; message=$(champ message)"

  for cote in 1024 2048; do
    bmp /tmp/gros.bmp $cote
    echo "--- E2 : BMP ${cote}x${cote}, $(wc -c < /tmp/gros.bmp) octets"
    r=$(upload /tmp/gros.bmp "exp4-e2-$cote" "$AK" "$AS")
    echo "HTTP $r ; message=$(champ message) ; public_id=$(champ public_id) ; bytes=$(champ bytes)"
    case "$r" in
      200*) echo "accepté : destroy HTTP $(supprimer "$(champ public_id)") ; result=$(champ result)" ;;
    esac
    rm -f /tmp/gros.bmp
  done

  echo "--- F : état final du dossier $DOSSIER"
  echo "liste par préfixe : HTTP $(admin "resources/image/upload?prefix=$DOSSIER/&max_results=100") ; $(grep -o '"public_id":"[^"]*"' "$REP" | wc -l) ressource(s)"
  echo "liste par asset_folder : HTTP $(admin "resources/by_asset_folder?asset_folder=$DOSSIER&max_results=100") ; $(grep -o '"public_id":"[^"]*"' "$REP" | wc -l) ressource(s)"
  c=$(printf 'user = "%s:%s"\n' "$AK" "$AS" | curl -sS --max-time 30 -K - -X DELETE -o "$REP" -w '%{http_code}' "$API/folders/$DOSSIER")
  echo "suppression du dossier : HTTP $c ; $(sed 's|\\/|/|g' "$REP" | head -c 300)"
  echo "=== fin $(date -u '+%T') UTC"
}

principal 2>&1 | masquer
