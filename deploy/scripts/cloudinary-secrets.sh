#!/usr/bin/env bash
# US1a — Secret cloudinary de app (identifiants de l'API Cloudinary lus par troc-service), à lancer sur le VPS
# AVANT le déploiement de la version de troc-service qui l'utilise : bash cloudinary-secrets.sh
# - les trois valeurs sont demandées sans écho (read -s), jamais affichées, jamais passées en argument d'une commande
#   (visibles dans ps) : elles transitent par des fichiers temporaires privés, effacés à la fin ;
# - un Secret existant n'est pas modifié (pour changer les valeurs : supprimer le Secret, accord du Lead Developer,
#   puis relancer ce script et redémarrer troc-service) ;
# - contrôle final : noms des clés et nombre de caractères seulement.
# Identifiants saisis à la main : seule exception à la génération sur le VPS (docs/tickets/README.md, « Mots de passe »).
set -euo pipefail
NS=app
NOM=cloudinary

if kubectl -n "$NS" get secret "$NOM" >/dev/null 2>&1; then
  echo "secret/$NOM déjà présent : inchangé"
else
  TMP=$(mktemp -d)
  chmod 700 "$TMP"
  trap 'rm -rf "$TMP"' EXIT

  demander() {
    local cle=$1 libelle=$2 valeur
    read -r -s -p "$libelle : " valeur
    echo
    if [ -z "$valeur" ]; then
      echo "valeur vide refusée : rien n'a été créé" >&2
      exit 1
    fi
    printf '%s' "$valeur" > "$TMP/$cle"
  }

  demander cloud-name "Cloudinary, cloud name"
  demander api-key "Cloudinary, API key"
  demander api-secret "Cloudinary, API secret"

  kubectl -n "$NS" create secret generic "$NOM" \
    --from-file=cloud-name="$TMP/cloud-name" \
    --from-file=api-key="$TMP/api-key" \
    --from-file=api-secret="$TMP/api-secret"
fi

# Contrôle : noms des clés et longueur des valeurs, aucune valeur.
kubectl -n "$NS" get secret "$NOM" \
  -o go-template='{{range $k, $v := .data}}{{$k}} : {{len (base64decode $v)}} caractères{{"\n"}}{{end}}'
