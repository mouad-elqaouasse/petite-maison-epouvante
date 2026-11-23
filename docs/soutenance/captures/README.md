# Captures de la soutenance

Preuves rangées par dossier : ce que chaque capture montre et le critère de la grille qu'elle sert
(`docs/sujet/grille.md` : E1 à E3, P1 à P3, Q1 à Q3, O1, O2).
Captures d'écran faites avec Playwright (pages publiques GitHub sans connexion ; application sur la pile E2E locale,
`e2e/compose.yaml`, comptes de test). Fichiers `.txt` : sorties de commandes relevées sur le VPS ou en local.

## `ci/` — chaîne de livraison continue (21/11/2026)

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `d8-run-front-e2e-rouge.png` | Preuve D8 de SQ-14 (PR #40, commit `0b0c822`, libellé de bouton cassé exprès) : `construire` vert, `e2e` rouge, `livraison / tag` et `livraison / verifier` non lancés. L'E2E bloque la livraison. | P1, Q2 |
| `main-runs-b5d4b56.png` | Après la fusion de la PR #40 : les 8 workflows de `main` verts sur `b5d4b56` (chaînes des 3 composants, E2E, SonarCloud, CodeQL, gitleaks, Trivy config) | P1, Q2, Q3 |
| `main-troc-service-chaine-verte.png` | Chaîne complète d'un composant : `construire` (tests, Trivy, image, push GHCR) → `e2e` → `tag` (commit `newTag`, Flux) → `verifier` (SHA en ligne en HTTPS) | P1 |
| `main-front-chaine-verte.png` | Même chaîne pour le front | P1 |
| `main-notification-service-chaine-verte.png` | Même chaîne pour `notification-service` | P1 |

## `application/` — tranche fonctionnelle, pile E2E locale, 1280 px (21/11/2026)

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `1280-liste-annonces.png` | Annonces publiées avec photo, catégorie, type et date (US1a) | E3 |
| `1280-formulaire-publication.png` | Formulaire de publication rempli, aperçu de la photo (US1a) | E3 |
| `1280-centres-interet.png` | Catégories suivies par le membre, enregistrées à chaque case (US2) | E3 |
| `1280-mes-notifications.png` | Un même membre reçoit une CONFIRMATION (sa publication) et deux ALERTES (catégories suivies), créées de façon asynchrone par RabbitMQ (US3) | E3, E2 |

## `us1a/` et `us1a-ui/` — écrans « Annonces » et « Publier une annonce »

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `*-formulaire*.png`, `*-liste*.png` (375 et 1280 px) | Écrans de US1a dans tous leurs états (vide, chargement, erreur, erreurs de saisie, après publication), mobile et ordinateur | E3, O1 |
| `us1a-ui/1280-liste-cloudinary.png` | Photos servies par Cloudinary (hébergement externe, ADR 0021) | E2 |
| `us1a-ui/*-focus-visible.png` | Focus clavier visible (accessibilité) | Q2 |
| `rapport.txt` (deux dossiers) | axe-core : 0 violation grave sur chaque écran et chaque taille (conformité RGAA partielle) | Q2 |
| `us1a/matrice-flux.txt` | Flux réseau autorisés et refusés avec US1a (dont la sortie HTTPS de `troc-service` vers Cloudinary) | Q3, E2 |

## `us2-ui/` — écran « Mes centres d'intérêt »

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `1280-*.png`, `375-suivies.png` | Tous les états de l'écran (aucune, suivies, chargement, erreur, échec d'enregistrement, focus) | E3, O1 |
| `rapport.txt` | axe-core 0 violation grave, clavier, cibles tactiles ≥ 44 px | Q2 |
| `matrice-flux.txt` | Matrice de flux après US2 : 0 écart sur 40 flux, contrôles HTTPS des abonnements | Q3, E2 |

## `us3-ui/` — écran « Mes notifications »

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `1280-*.png`, `375-*.png` | Tous les états de l'écran (liste, vide, chargement, erreur, focus), mobile et ordinateur | E3, O1 |
| `rapport.txt` | axe-core 0 violation grave, clavier, pas de défilement horizontal à 375 px | Q2 |

## `us3/` — résilience de la notification asynchrone

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `resilience.txt` | Sur le VPS : `notification-service` arrêté, annonce publiée, message conservé dans la file, service relancé, CONFIRMATION et ALERTE créées, DLQ vide (06/11/2026) | E2, P3 |

## `sq-08/` — NetworkPolicies (refus par défaut)

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `avant.txt`, `apres.txt` | Un pod intrus atteint les services avant les NetworkPolicies, plus après | Q3 |
| `matrice-00-reference.txt` à `matrice-08-*.txt` | Matrice de flux après chaque politique ajoutée : seuls les flux prévus passent | Q3, E2 |
| `matrice-redemarrage-*.txt` | Chaque composant redémarré : la matrice reste conforme | Q3, P3 |

## `sq-13/` — RabbitMQ persistant

| Capture | Ce qu'elle prouve | Critères |
|---|---|---|
| `avant.txt`, `apres.txt` | Messages en attente perdus à la recréation du pod avant SQ-13, conservés après (StatefulSet, volume persistant, ADR 0018) | P3, E2 |
| `verifications.txt`, `matrice-apres-bascule.txt` | Santé des services et flux réseau après la bascule | P3, Q3 |

## Captures à faire par le Lead Developer (connexion requise)

Ces captures demandent une connexion avec mot de passe : elles ne sont pas faites par l'assistant.

| Capture attendue | Où | Critères |
|---|---|---|
| Tableau de bord Grafana « Santé » (cibles UP, DLQ, RAM) et panneau « Événements AnnoncePubliee » | Grafana par tunnel SSH, compte administrateur | P3, Q1 |
| Tableau de bord Grafana « Montée en charge » pendant une campagne JMeter (replicas HPA, latence, CPU) | Grafana, juste après chaque test (historique perdu à la recréation du pod) | P3, Q1 |
| Quality Gate SonarCloud (couverture, dette, duplications) | sonarcloud.io, compte GitHub | Q1, Q2 |
| Onglet Security du dépôt (alertes CodeQL, Dependabot, Trivy) | GitHub, connecté comme propriétaire | Q3 |
| Réglages Dependabot et protection de `main` (après SQ-11) | GitHub, réglages du dépôt | P1, Q3 |
| Application en production : connexion Keycloak puis écrans | https://epouvante.menuly-app.com, comptes de démonstration | E3, P3 |
