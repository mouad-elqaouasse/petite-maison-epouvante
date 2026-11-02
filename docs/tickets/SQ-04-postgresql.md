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

## Résultats (02/11/2026) — ticket clos

- **PR [#16](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/16)** : tous les contrôles verts
  (SonarCloud, `analyse`, construction des deux services, `secrets`) ; livrée par Flux (révision `a165577`, images `c8423ef`).
- **Décision D8** : Flyway au démarrage avec réessais, `db` dans `readiness` et pas dans `liveness` (ADR 0013).
- **Tests d'intégration** (Testcontainers PostgreSQL 18.6 + Toxiproxy), 3 par service, verts en local et en CI :
  santé `db` UP et migration V1 appliquée ; base indisponible 5 s au démarrage → le service attend puis démarre ;
  base coupée en fonctionnement → `readiness` 503, `liveness` 200, puis `readiness` 200.
- **Secrets** `postgresql-admin`, `troc-db`, `notification-db`, `keycloak-db` créés dans `app` (`openssl rand -hex 24`,
  jamais affichés).
- **Sur le VPS** :
  - santé `db` UP dans les deux services ; groupe `readiness` = `db` + `readinessState` ;
  - isolation : chaque rôle entre dans sa base ; `troc` refusé sur `notification`, `keycloak` et `postgres`,
    `notification` refusé sur `troc` (« User does not have CONNECT privilege ») ;
  - migration V1 appliquée dans `troc` et `notification`, chacune par son propre rôle ; PostgreSQL tourne en uid 70.
- **Suppression du pod PostgreSQL** (14:53:35 UTC) : nouveau pod démarré en ≈ 2 s et prêt avant la première mesure (7 s) ;
  `/actuator/health/readiness` des deux services sans réponse à 7 s (délai de `wget` de 2 s), 200 à 12 s ; **aucun
  redémarrage** des services ; historique Flyway conservé (volume `local-path`).
- **Écart accepté** : `readiness` DOWN n'a pas été vu sur le VPS. La coupure (≈ 2 à 3 s) est plus courte que les 3 échecs
  de sonde (≈ 15 s) nécessaires pour que Kubernetes retire les services. Le passage DOWN puis UP est prouvé par le test
  d'intégration (Toxiproxy), pas sur le cluster.
- **Incident de manipulation** : une première commande, interrompue, avait déjà supprimé le pod précédent (14:51:37 UTC) ;
  le pod PostgreSQL a donc été supprimé deux fois, sans effet sur les services (0 redémarrage).
- **Sonde PostgreSQL** : un échec ponctuel à 14:37 UTC (« `pg_isready` timed out after 1s »). À surveiller ; si cela se
  répète, porter `timeoutSeconds` de la sonde à 3 s.
- **RAM** (`kubectl top`, 02/11/2026) : PostgreSQL **58 Mio** (budget 80 Mio au pic), `troc-service` 179 Mio,
  `notification-service` 170 Mio, front 6 Mio ; nœud 1 901 Mio (49 %) ; `free -m` : 1 756 Mio disponibles, swap 73 Mio.
- **Schémas** : `docs/schemas/base-de-donnees.md` (organisation des bases, modèle cible) et ses deux PNG.
- **Suite** : la base `keycloak` et le Secret `keycloak-db` serviront à SQ-06.
