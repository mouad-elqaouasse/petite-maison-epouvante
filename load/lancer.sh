#!/usr/bin/env bash
# Lance une campagne JMeter en mode non graphique depuis le poste, puis produit le rapport HTML (p95 par requête).
# Usage (Git Bash ou WSL2) : load/lancer.sh <utilisateurs> [durée en s, montée comprise = 300] [montée en s = 60]
#   exemple, palier à 10 membres pendant 5 min : load/lancer.sh 10 300 60
# Prérequis : JMeter 5.6.3 (jmeter dans le PATH, ou JMETER=chemin/vers/jmeter.bat) ;
#             load/charge.properties avec client_secret=… (README, jamais commité).
set -euo pipefail

UTILISATEURS=${1:?usage : lancer.sh <utilisateurs> [durée] [montée]}
DUREE=${2:-300}
MONTEE=${3:-60}
JMETER=${JMETER:-jmeter}

cd "$(dirname "$0")"
[ -s charge.properties ] || { echo "ERREUR : load/charge.properties absent (voir README)" >&2; exit 1; }
grep -q '^client_secret=.\+' charge.properties || { echo "ERREUR : client_secret vide dans charge.properties" >&2; exit 1; }

NOM=$(date -u +%Y%m%dT%H%M%SZ)-${UTILISATEURS}u-${DUREE}s
mkdir -p resultats
echo "Campagne $NOM : $UTILISATEURS membres, montée $MONTEE s, durée $DUREE s"
"$JMETER" -n -t plan-charge.jmx -q charge.properties \
  -Jutilisateurs="$UTILISATEURS" -Jduree="$DUREE" -Jmontee="$MONTEE" -Jphoto=photo-charge.png \
  -l "resultats/$NOM.jtl" -j "resultats/$NOM.log" -e -o "resultats/$NOM"
echo "Rapport : load/resultats/$NOM/index.html (Statistics : colonne 95th pct)"
