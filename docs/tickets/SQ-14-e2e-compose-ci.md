# SQ-14 — Tests E2E dans la CI (Docker Compose, Playwright, axe-core)

| | |
|---|---|
| Dépend de | US1a (premier parcours à tester) ; scénario 2 après US3 |
| Sources | `cadrage.md` §1.3.12 (E2E dans la CI, avant publication) et §5 (Should) ; décision D4 du 05/11/2026 (E2E hors d'US1a, dans un ticket à part) ; `docs/backlog/US1a-publier-annonce.md` (accessibilité vérifiée par axe-core en E2E) |
| MoSCoW | Should (ajouté le 05/11/2026) |

## But

Avant toute publication d'image sur GHCR, la CI lance toute l'application dans Docker Compose et rejoue les parcours
principaux dans un vrai navigateur, avec un contrôle d'accessibilité.

## Contenu

- `e2e/compose.yaml` : PostgreSQL, RabbitMQ (définitions de test), Keycloak avec un realm de test (comptes de test,
  jamais les comptes de démonstration), `troc-service` en profil `photo-factice`, `notification-service`, front nginx
  (image réelle, CSP comprise). Images construites par la CI, **celles qui seront publiées** (même digest).
- `e2e/` : Playwright + `@axe-core/playwright`, à 375 et 1280 px.
  1. Connexion Keycloak (PKCE), publication d'une annonce avec photo, annonce visible dans la liste (US1a).
  2. Un membre abonné voit la notification (après US3).
- axe-core : 0 violation grave, bloquant ; captures et rapport en artefact du workflow.
- Workflow : job `e2e` requis par la protection de `main` (SQ-11) ; publication GHCR seulement s'il est vert.
- Faux stockage des photos : les URL factices sont servies par une route Playwright (comme la vérification locale d'US1a,
  `docs/soutenance/captures/us1a/`), aucun appel au vrai Cloudinary.

## Vérification

- Un test volontairement cassé (libellé de bouton modifié) fait échouer le job ; la publication n'a pas lieu.
- Durée du job mesurée (objectif : moins de 10 min).

## Limites connues

- RAM du runner GitHub (7 Go) : toute la pile doit tenir, Keycloak compris.
- axe-core ne couvre qu'une partie des critères RGAA (audit manuel au plan de remédiation, `cadrage.md` §8).
