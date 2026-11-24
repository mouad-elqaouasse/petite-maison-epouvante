#!/usr/bin/env bash
# Données des campagnes de charge (décision D7 du plan de charge, 24/11/2026). À lancer sur le VPS, depuis le poste :
#   ssh ubuntu@51.178.39.218 'bash -s -- sauvegarder' < load/donnees-charge.sh
#   ssh ubuntu@51.178.39.218 'bash -s -- semer'       < load/donnees-charge.sh
#   ssh ubuntu@51.178.39.218 'bash -s -- compter'     < load/donnees-charge.sh
#   ssh ubuntu@51.178.39.218 'CONFIRMER=oui bash -s -- nettoyer' < load/donnees-charge.sh
#
# sauvegarder : pg_dump des bases troc et notification dans ~/sauvegardes-charge (droits 600), contrôlé par pg_restore -l.
# semer       : 20 abonnés fictifs par catégorie (membre_id « charge-<catégorie>-NN »), refusé sans sauvegarde de moins de 24 h.
# compter     : annonces du compte de service, leurs notifications, abonnés fictifs (lecture seule).
# nettoyer    : supprime les notifications des annonces de charge, ces annonces et les abonnés fictifs. Irréversible :
#               exige CONFIRMER=oui. À lancer AVANT keycloak-client-charge.sh supprimer (sub lu dans le Secret charge-jmeter).
#
# Annonces de charge reconnues par leur auteur (sub du compte de service charge-jmeter), jamais par leur titre.
# PostgreSQL n'est que lu ou modifié par psql : aucun redémarrage de pod (CLAUDE.md, Méthode de travail).
set -euo pipefail

ACTION=${1:?usage : sauvegarder | semer | compter | nettoyer}
NS=app
DOSSIER=$HOME/sauvegardes-charge

pg() { kubectl -n "$NS" exec -i deploy/postgresql -- psql -U postgres -X -q -v ON_ERROR_STOP=1 "$@"; }

lire_sub() {
  SUB=${SUB:-$(kubectl -n "$NS" get secret charge-jmeter -o 'jsonpath={.data.sub}' 2>/dev/null | base64 -d)}
  [[ "$SUB" =~ ^[0-9a-f-]{36}$ ]] || { echo "ERREUR : sub du compte de service introuvable (Secret charge-jmeter ou variable SUB)" >&2; exit 1; }
}

# Identifiants des annonces de charge, une par ligne (base troc).
ids_annonces() {
  pg -d troc -At -v sub="$SUB" <<'SQL'
select id from annonce where auteur_id = :'sub';
SQL
}

# Exécute du SQL dans la base notification avec la table temporaire ids (annonces de charge) déjà remplie.
avec_ids() {
  { echo "create temp table ids (id uuid primary key);"
    echo "copy ids from stdin;"
    ids_annonces
    echo '\.'
    cat
  } | pg -d notification -At
}

compter() {
  lire_sub
  echo "--- sub du compte de service : $SUB"
  echo "troc : annonces de charge = $(ids_annonces | grep -c . || true)"
  avec_ids <<'SQL'
select 'notification : ' || type || ' = ' || count(*) from notification n join ids on n.annonce_id = ids.id group by type order by type;
select 'notification : abonnés fictifs = ' || count(*) from abonnement where membre_id like 'charge-%';
SQL
}

case "$ACTION" in
  sauvegarder)
    mkdir -p "$DOSSIER" && chmod 700 "$DOSSIER"
    horodatage=$(date -u +%Y%m%dT%H%M%SZ)
    for base in troc notification; do
      fichier="$DOSSIER/$base-$horodatage.dump"
      kubectl -n "$NS" exec deploy/postgresql -- pg_dump -U postgres -Fc "$base" > "$fichier"
      chmod 600 "$fichier"
      entrees=$(kubectl -n "$NS" exec -i deploy/postgresql -- pg_restore -l < "$fichier" | grep -c 'TABLE DATA' || true)
      echo "$fichier : $(du -h "$fichier" | cut -f1), $entrees tables avec données"
      [ "$entrees" -gt 0 ] || { echo "ERREUR : sauvegarde de $base vide ou illisible" >&2; exit 1; }
    done
    ;;
  semer)
    recente=$(find "$DOSSIER" -name 'notification-*.dump' -mmin -1440 2>/dev/null | head -1 || true)
    [ -n "$recente" ] || { echo "ERREUR : aucune sauvegarde de moins de 24 h, lancer d'abord « sauvegarder »" >&2; exit 1; }
    echo "--- sauvegarde trouvée : $recente"
    pg -d notification <<'SQL'
insert into abonnement (membre_id, categorie)
select 'charge-' || lower(c) || '-' || lpad(n::text, 2, '0'), c
from unnest(array['FIGURINES', 'FILMS', 'BD', 'JEUX_DE_SOCIETE', 'GOODIES']) as c, generate_series(1, 20) as n
on conflict do nothing;
SQL
    pg -d notification -At <<'SQL'
select 'abonnés fictifs par catégorie : ' || string_agg(categorie || ' ' || nb, ', ' order by categorie)
from (select categorie, count(*) nb from abonnement where membre_id like 'charge-%' group by categorie) t;
SQL
    ;;
  compter)
    compter
    ;;
  nettoyer)
    [ "${CONFIRMER:-}" = oui ] || { echo "ERREUR : suppression irréversible, relancer avec CONFIRMER=oui" >&2; exit 1; }
    lire_sub
    echo "--- avant"
    compter
    avec_ids <<'SQL'
delete from notification n using ids where n.annonce_id = ids.id;
delete from abonnement where membre_id like 'charge-%';
SQL
    pg -d troc -v sub="$SUB" <<'SQL'
delete from annonce where auteur_id = :'sub';
SQL
    echo "--- après"
    compter
    ;;
  *)
    echo "usage : sauvegarder | semer | compter | nettoyer" >&2
    exit 2
    ;;
esac
