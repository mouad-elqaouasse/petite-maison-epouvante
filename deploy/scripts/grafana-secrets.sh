#!/usr/bin/env bash
# SQ-07 — Secret Grafana de app, à lancer sur le VPS AVANT le premier démarrage de Grafana :
#   ssh ubuntu@51.178.39.218 'bash -s' < grafana-secrets.sh
# - grafana-admin : password (administrateur admin, lu par le pod au démarrage).
# Mot de passe généré ici (openssl rand), jamais affiché. Un Secret existant n'est pas modifié.
# Lecture par toi : kubectl -n app get secret grafana-admin -o jsonpath='{.data.password}' | base64 -d
set -euo pipefail
NS=app

if kubectl -n "$NS" get secret grafana-admin >/dev/null 2>&1; then
  echo "secret/grafana-admin déjà présent : inchangé"
else
  kubectl -n "$NS" create secret generic grafana-admin --from-literal=password="$(openssl rand -hex 24)"
fi
