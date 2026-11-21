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

## Décisions (validées le 21/11/2026, ADR 0027)

- **D1** : un workflow par composant, sans refonte. build → tests → image → Trivy → push GHCR → E2E (`_e2e.yml`) avec
  cette image, les autres composants à leur tag déployé → commit `newTag` seulement si l'E2E est vert. Sur une PR,
  l'image passe par un artefact du run. `e2e.yml` teste les tags déployés quand seuls `e2e/` ou `_e2e.yml` changent.
- **D2** : adresses de la prod dans le navigateur, portées par la passerelle Traefik (alias réseau, certificat
  auto-signé) : l'image publiée du front est testée telle quelle, CSP comprise.
- **D3** : realm de test généré depuis `deploy/app/keycloak/realm-epouvante.json`, comptes `e2e-auteur` et `e2e-abonne`.
- **D4** : base des URL factices réglable dans `troc-service` (`app.photo-factice.url-base`), réglée sur
  `res.cloudinary.com` et servie par une route Playwright.
- **D5** : parcours 2 dans deux navigateurs (abonné et auteur), « Actualiser » pendant 30 s au plus.
- **D6** : contrôle requis par la protection de `main` reporté à SQ-11, après la migration du dépôt.
- **D7** : E2E sur chaque PR concernée et sur `main` avant le tag.
- **D8** : test cassé prouvé sur la branche de la PR (commit, puis commit de retour), sans réécriture d'historique.
- Limite (conséquence de D1) : un changement entre composants doit rester compatible avec la version déployée de
  l'autre composant (back livré avant le front).

## Vérification

- Un test volontairement cassé (libellé de bouton modifié) fait échouer le job ; la publication n'a pas lieu.
- Durée du job mesurée (objectif : moins de 10 min).

## Limites connues

- RAM du runner GitHub (7 Go) : toute la pile doit tenir, Keycloak compris.
- axe-core ne couvre qu'une partie des critères RGAA (audit manuel au plan de remédiation, `cadrage.md` §8).
