# SQ-04 — PostgreSQL et connexion des services

| | |
|---|---|
| Dépend de | SQ-02 |
| Sources | `cadrage.md` §1.4.15 (secrets), §1.4.18 (64/256 Mio, une instance, une base par service) ; ADR 0004 §4 (réessai des connexions) ; ADR 0013 (Flyway au démarrage, avec réessais) ; rapport exp. 1 §8 |

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
  `readiness` et pas dans `liveness` ; Flyway migre au démarrage et réessaie la connexion au lieu de faire échouer le
  démarrage, `startupProbe` allongée en conséquence (ADR 0004 §4, ADR 0013).

## Critères d'acceptation

- Tests d'intégration (écrits d'abord, Testcontainers PostgreSQL) : santé `db` UP ; migration appliquée.
- Test : base indisponible pendant les premières secondes du démarrage → le service tolère l'attente (réessais de
  Flyway) et démarre dès que la base répond (ADR 0013).
- Test : base coupée en cours de fonctionnement → `readiness` DOWN et `liveness` UP, puis `readiness` UP dès que la
  base répond.
- Sur le VPS : le rôle `troc` ne peut pas se connecter à la base `notification` (et inversement).
- Suppression du pod PostgreSQL → readiness des services DOWN puis UP, sans redémarrage des services ; données conservées.
- RAM mesurée.

## Notes de réalisation

- TDD : `BaseDeDonneesTest` de `troc-service` a été vu rouge (aucune base câblée) avant l'implémentation, puis vert.
  Celui de `notification-service`, recopié de `troc-service`, **n'a pas été vu rouge** : il a été lancé directement
  après l'implémentation (vert).
