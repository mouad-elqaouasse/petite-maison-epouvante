# Phase « squelette de l'application » (walking skeleton) — plan des tickets

Date : 30/10/2026. Statut : **validé le 30/10/2026** (ordre proposé, décisions D1 à D5 et D7 ; D6 en bonus).
Sources : `docs/cadrage.md`, `docs/adr/0001` à `0005`, sections « Limites » et « Impact sur le cadrage » des rapports
`docs/experimentations/exp1` à `exp4`, et la liste « À reprendre au déploiement de `app` » (journal de l'expérience 1).

## Objectif

Déployer dans le namespace `app` les **vrais composants, presque vides**, livrés automatiquement par la chaîne de
l'expérience 3 (GitHub Actions → GHCR → commit du tag → Flux). Aucune fonctionnalité métier : les user stories
(`docs/backlog/`) viendront se brancher sur ce squelette.

## Pourquoi ces tickets sont des tranches verticales

Chaque ticket traverse toutes les couches : code (avec ses tests), image, CI, manifests `deploy/app/`, déploiement par Flux
et **vérification sur l'URL publique en HTTPS**. Un ticket n'est terminé que lorsque son résultat est visible en ligne
(ou par tunnel pour ce qui n'est pas public) et que la RAM du nœud a été mesurée (`kubectl top`, `free -m`).
Aucun ticket « toute la base », « toute la CI » ou « tous les manifests ».

## Liste et ordre

| # | Ticket | Résultat visible | Dépend de | MoSCoW |
|---|---|---|---|---|
| SQ-00 | [Bascule `sandbox` → `app`](SQ-00-bascule-sandbox-app.md) | `sandbox` supprimé, Secrets TLS copiés, Flux pointe sur `deploy/app/` | — | Must |
| SQ-01 | [Premier fil : `troc-service` /version livré en HTTPS](SQ-01-troc-service-version.md) | `https://epouvante.…/api/troc/version` renvoie le SHA du commit | SQ-00 | Must |
| SQ-02 | [`notification-service` sur la même chaîne](SQ-02-notification-service-version.md) | `…/api/notifications/version` renvoie son SHA | SQ-01 | Must |
| SQ-03 | [Front Angular : page des versions](SQ-03-front-versions.md) | la page d'accueil affiche les 3 versions | SQ-02 | Must |
| SQ-04 | [PostgreSQL et connexion des services](SQ-04-postgresql.md) | santé `db` UP dans les deux services | SQ-02 | Must |
| SQ-05 | [RabbitMQ, comptes et connexion des services](SQ-05-rabbitmq.md) | santé `rabbit` UP dans les deux services | SQ-02 | Must |
| SQ-06 | [Keycloak, realm et validation des jetons](SQ-06-keycloak-jetons.md) | connexion PKCE depuis la page, `/moi` affiche l'utilisateur et ses rôles | SQ-03, SQ-04 | Must |
| SQ-07 | [Prometheus, kube-state-metrics et Grafana](SQ-07-observabilite.md) | toutes les cibles UP, tableau de bord « Squelette » par tunnel | SQ-05 | Must |
| SQ-08 | [NetworkPolicies](SQ-08-networkpolicies.md) | matrice de flux conforme, application toujours fonctionnelle | SQ-06, SQ-07 | Should |
| SQ-09 | [SonarCloud (Quality Gate bloquant)](SQ-09-sonarcloud.md) | Quality Gate visible sur chaque PR | SQ-02 | Must |
| SQ-10 | [CodeQL et Dependabot](SQ-10-codeql-dependabot.md) | alertes dans l'onglet Security | SQ-03 | Should |
| SQ-11 | [Protection de `main`](SQ-11-protection-main.md) | PR obligatoire, contrôles requis, commit du tag toujours possible | SQ-03, SQ-09 | Must |
| SQ-12 | [Image Keycloak optimisée (bonus)](SQ-12-keycloak-optimise.md) | démarrage de Keycloak plus court et pic de mémoire plus bas | SQ-06 | Could |

```
SQ-00 ─► SQ-01 ─► SQ-02 ─┬─► SQ-03 ─┬─────────────► SQ-06 ─┐
                         │          ├─► SQ-10              │
                         │          └─► SQ-11 ◄─ SQ-09     ├─► SQ-08
                         ├─► SQ-04 ───────────► (SQ-06)    │
                         ├─► SQ-05 ─► SQ-07 ───────────────┘
                         └─► SQ-09
SQ-06 ─► SQ-12 (bonus)
```

Ordre de travail proposé (une session par ticket, une PR par ticket) :
SQ-00, SQ-01, SQ-02, SQ-03, SQ-09, SQ-04, SQ-05, SQ-06, SQ-07, SQ-11, SQ-10, SQ-08, puis SQ-12 s'il reste du temps.
SQ-09 passe tôt pour que le Quality Gate s'applique au code des tickets suivants ; SQ-08 reste en dernier,
application fonctionnelle (`docs/cadrage.md` §1.4.14, ADR 0004).

## Décisions (validées le 30/10/2026)

| # | Décision | Raison | Ticket | ADR |
|---|---|---|---|---|
| D1 | **Routage par préfixe sur un seul hôte** : `/` → front, `/api/troc/**` → `troc-service`, `/api/notifications/**` → `notification-service` (`epouvante.…`). | même origine pour le front : pas de CORS ; un seul certificat déjà émis. | SQ-01 | 0006 |
| D2 | **Port de gestion séparé** (actuator sur 8081, non référencé par aucun Ingress). | `/actuator/prometheus` jamais exposé (`cadrage.md` §1.4.16), sans règle de filtrage dans Traefik. | SQ-01 | 0007 |
| D3 | **Namespace `app` et Secrets créés à la main, hors de Flux.** | avec `prune: true`, retirer le namespace du dépôt supprimerait aussi les Secrets et les certificats copiés. | SQ-00 | 0008 |
| D4 | **Workflow réutilisable** pour les services Java (`_service-java.yml`) appelé par un workflow par composant filtré par chemin ; mise à jour du tag dans un job sérialisé (`concurrency`) avec `git pull --rebase` avant le push. | deux composants livrés en même temps ne doivent pas se marcher dessus sur `main`. | SQ-01 | 0009 |
| D5 | **Comptes et topologie RabbitMQ chargés au démarrage** (`load_definitions`) depuis un Secret généré sur le VPS. | stockage `emptyDir` (exp. 2, limites) : sans cela, un redémarrage du pod efface les comptes. | SQ-05 | 0010 |
| D6 | **Bonus** : image Keycloak optimisée construite par notre CI (`kc.sh build`, puis `start --optimized`). Keycloak standard d'abord. | ADR 0003 et rapport de l'exp. 1 : pic de mémoire à la limite (768 Mio) au démarrage. | SQ-12 | — (ADR si réalisé) |
| D7 | **Endpoint `GET /api/<service>/moi`** (authentifié) dans chaque service : renvoie `sub`, nom et rôles. | seul moyen de démontrer la validation des jetons sans fonctionnalité métier ; servira de modèle aux US. | SQ-06 | 0011 |

## Comptes de démonstration (validé le 30/10/2026)

Le realm `epouvante` de `app` contient des **comptes de démonstration** (`membre-demo`, `abonne-demo`, `moderateur-demo`),
jamais utilisés par la CI ni par les tests automatiques (`docs/cadrage.md` §1.3 point 12, corrigé le 30/10/2026).

## Mots de passe (validé le 30/10/2026)

**Tous** les mots de passe de `app` sont générés sur le VPS (`openssl rand`), écrits directement dans des Secrets
Kubernetes, jamais affichés dans le terminal, les logs ni la conversation : PostgreSQL, RabbitMQ, administrateur
Keycloak, comptes de démonstration, administrateur Grafana. Tu les lis toi-même (`kubectl get secret … -o jsonpath … | base64 -d`).
Seuls les identifiants de services externes (Cloudinary, US1b) restent saisis par toi.

## Ce qui doit être fait par toi

| Quand | Action |
|---|---|
| Chaque ticket VPS | Donner ton accord avant chaque commande sur le VPS (SQ-00 : inventaire, copie des Secrets TLS, suppression de `sandbox`). |
| SQ-01, 02, 03 (et SQ-12) | Après le premier push de chaque image : rendre public le paquet GHCR (`troc-service`, `notification-service`, `front`, et `keycloak` si SQ-12) dans GitHub (*Package settings → Change visibility*), comme pour `exp3-hello`. Sinon containerd ne peut pas télécharger l'image sans clé. |
| SQ-06, SQ-07 | Lire toi-même les mots de passe générés (administrateur Keycloak, comptes de démonstration, Grafana). |
| SQ-09 | Créer l'organisation et le projet SonarCloud (connexion GitHub), désactiver l'analyse automatique, ajouter le secret `SONAR_TOKEN` dans GitHub Actions. |
| SQ-10 | Activer CodeQL (*default setup* ou workflow), Dependabot alerts et security updates dans les réglages du dépôt. |
| SQ-11 | Créer le ruleset de `main` (et, selon l'option retenue, celui de la branche `deploy`) — réglage réservé au propriétaire du dépôt. |

## Hors de cette phase

Fonctionnalités des US, dashboards « Santé » et « Montée en charge », 3 alertes, HPA, E2E Compose + Playwright,
OWASP ZAP, rate limiting et `externalTrafficPolicy: Local`, sortie vers Cloudinary (avec US1b), campagnes JMeter.
