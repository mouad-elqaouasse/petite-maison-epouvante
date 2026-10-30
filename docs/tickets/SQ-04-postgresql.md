# SQ-04 — PostgreSQL et connexion des services

| | |
|---|---|
| Dépend de | SQ-02 |
| Sources | `cadrage.md` §1.4.15 (secrets), §1.4.18 (64/256 Mio, une instance, une base par service) ; ADR 0004 §4 (réessai des connexions) ; rapport exp. 1 §8 |

## But

Chaque service joint sa propre base, avec son propre compte, et le signale dans sa santé.

## Contenu

- `deploy/app/postgresql/` : PostgreSQL 18 (image épinglée, celle de l'exp. 1), 1 pod (64/256 Mio), volume persistant
  (`local-path` de K3s), non-root ; Service interne seulement.
- Initialisation : bases `troc`, `notification` et `keycloak` (pour SQ-06), **un rôle par base** sans droit sur les autres ;
  script d'init dans une ConfigMap, mots de passe lus dans des Secrets.
- Secrets créés sur le VPS (mots de passe générés par `openssl rand`, jamais affichés) : `postgresql-admin`,
  `troc-db`, `notification-db`, `keycloak-db`. Les manifests ne citent que leur nom.
- Services : datasource + Flyway (première migration vide ou table technique), indicateur de santé `db` dans le groupe
  `readiness` ; le pool réessaie au lieu de faire échouer le démarrage (ADR 0004 §4).

## Critères d'acceptation

- Tests d'intégration (écrits d'abord, Testcontainers PostgreSQL) : santé `db` UP ; migration appliquée.
- Test : démarrage sans base joignable → le service démarre, `readiness` DOWN, puis UP dès que la base répond.
- Sur le VPS : le rôle `troc` ne peut pas se connecter à la base `notification` (et inversement).
- Suppression du pod PostgreSQL → readiness des services DOWN puis UP, sans redémarrage des services ; données conservées.
- RAM mesurée.
