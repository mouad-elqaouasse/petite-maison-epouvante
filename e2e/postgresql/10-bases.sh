#!/bin/sh
# SQ-14 — Bases et rôles des services, comme deploy/app/postgresql (une base par service) ; mots de passe de test.
# Keycloak garde sa base de développement (start-dev) : la pile E2E n'en a pas besoin.
set -eu
psql -v ON_ERROR_STOP=1 --username postgres \
  -v troc_mdp="$MDP_TROC" -v notification_mdp="$MDP_NOTIFICATION" <<'SQL'
CREATE ROLE troc LOGIN PASSWORD :'troc_mdp';
CREATE DATABASE troc OWNER troc ENCODING 'UTF8';
CREATE ROLE notification LOGIN PASSWORD :'notification_mdp';
CREATE DATABASE notification OWNER notification ENCODING 'UTF8';
SQL
