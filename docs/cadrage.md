# Cadrage du POC — Publier une annonce de troc et notifier les membres intéressés

Date : 27/10/2026. Issu d'une séance de questions/réponses sur `docs/sujet/`.
Sources : `docs/sujet/contexte.md`, `docs/sujet/consignes.md`, `docs/sujet/grille.md`, `CLAUDE.md`.
Les critères de la grille (E1…O2) sont indiqués entre crochets.

## 1. Décisions prises

### 1.1 Métier [E3]
1. **Centres d'intérêt** : liste fermée de catégories (Figurines, Films, BD, Jeux de société, Goodies — `contexte.md` §1).
   Une annonce porte une seule catégorie ; un membre s'abonne à 0..n catégories. Correspondance par égalité.
2. **Annonce** : `titre` (3–80), `description` (≤ 1000), `categorie`, `type` (`ECHANGE` | `DON`), `statut`, `creeLe`.
   `auteurId` vient du `sub` du JWT (JSON Web Token), jamais du corps de la requête. Validation Bean Validation, 400 si invalide.
3. **Photo obligatoire** : exactement 1 photo, 2 Mo max, JPEG/PNG/WebP, type vérifié par les premiers octets (magic bytes).
   - Upload multipart via `troc-service`, vérification côté serveur, puis envoi à Cloudinary derrière l'interface `StockagePhoto`.
   - L'annonce stocke `photoPublicId` et `photoUrl`. Si l'enregistrement en base échoue, la photo est supprimée (pas d'orphelin).
   - Upload réservé à l'auteur connecté. La photo est envoyée telle quelle (pas de réencodage ni de suppression EXIF en V1).
4. **Notification in-app uniquement** : stockée en base, lue via `GET /notifications`, marquage « lue ».
   Deux types : `CONFIRMATION` (« Votre annonce est publiée », pour l'auteur) et `ALERTE` (« Nouvelle annonce », pour les abonnés
   à la catégorie). Un auteur abonné à la catégorie reçoit seulement la confirmation.
5. **Modération a posteriori** : publication immédiate (`PUBLIEE`) ; un `MODERATEUR` peut retirer une annonce (`RETIREE`) ;
   un `USER` reçoit 403.
6. **Liste des annonces réservée aux membres authentifiés** (seuls news et articles sont publics, `contexte.md` §2).

### 1.2 Architecture [E2]
7. **Propriété des données** : `notification-service` détient les abonnements. `troc-service` publie l'événement `AnnoncePubliee`
   (id, catégorie, auteur, titre, date, URL photo) sans connaître les destinataires.
8. **Livraison « au moins une fois »** :
   - producteur : publication après commit (`@TransactionalEventListener(AFTER_COMMIT)`), publisher confirms, messages et files durables ;
   - consommateur : ack après écriture en base, unicité `(membreId, annonceId, type)` (idempotence), 3 tentatives puis DLQ (dead letter queue).
   - Limite assumée : perte possible entre commit et publication (voir remédiation, outbox).
9. **Authentification / autorisation** [Q3] :
   - Angular, client public : Authorization Code + PKCE (Proof Key for Code Exchange) avec **keycloak-angular** ; jeton en mémoire,
     access token court (~5 min), refresh token avec rotation.
   - Services : resource servers Spring Security ; vérification signature (JWKS), `iss`, `aud` (mapper Keycloak), `exp` ;
     rôles du realm mappés en autorités ; RBAC (Role-Based Access Control) côté serveur via `@PreAuthorize`.
   - `issuer-uri` = URL publique de Keycloak, `jwk-set-uri` = URL interne du cluster.
10. **RabbitMQ** : un compte par service au moindre privilège (troc : publication sur l'exchange ; notification : lecture de sa file et de la DLQ).

### 1.3 Qualité et tests [Q1, Q2]
11. **Indicateurs** :

| # | Objectif (ISO 25010) | Indicateur | Seuil | Outil / moment |
|---|---|---|---|---|
| 1 | Fonctionnalité | % de critères d'acceptation dont le test passe | 100 % (bloquant) | Tests d'acceptation, CI |
| 2 | Maintenabilité | Quality Gate SonarCloud : couverture du nouveau code, ratio de dette | ≥ 80 %, ≤ 5 %, note A (bloquant) | JaCoCo + SonarCloud, chaque PR |
| 3 | Performance | Latence p95 de `POST /annonces` sous charge | < 500 ms à N utilisateurs | JMeter + Prometheus, campagnes de charge |
| 4 | Fiabilité | Taux de 5xx + messages en DLQ | < 1 % et 0 | Prometheus / Grafana, alertes |
| 5 | Sécurité (bonus) | Vulnérabilités critiques/hautes | 0 (bloquant) | Trivy, CI |

   Les indicateurs 1, 2, 5 empêchent la dette d'entrer dans `main` ; 3 et 4 sont suivis dans le temps pour détecter les dérives.
12. **Tests** :
    - unitaires (JUnit 5) ;
    - intégration (Testcontainers : PostgreSQL, RabbitMQ, Keycloak avec realm de test ; WireMock pour l'adaptateur Cloudinary) ;
    - **Cloudinary dans les tests** (décision du 27/10/2026) : tous les tests de l'application (acceptation, intégration, E2E, charge)
      utilisent une fausse implémentation de `StockagePhoto` ; l'adaptateur Cloudinary est testé seul contre une API simulée
      par WireMock (succès, erreur, timeout) ; **aucun appel au vrai Cloudinary en CI**. Seule exception, hors CI : la courte
      campagne de charge avec le vrai Cloudinary sur le VPS (point 17) ;
    - **acceptation par l'API = la base** : boîte noire, JUnit 5 + RestAssured + Awaitility, `StockagePhoto` simulé.
      Critères en Gherkin dans `docs/backlog/`, un test JUnit par scénario (même nom), sans Cucumber ;
    - **E2E dans la CI, avant publication** : Docker Compose lance toute l'application (PostgreSQL, RabbitMQ, Keycloak + realm de test,
      deux services, front, Cloudinary remplacé par une fausse implémentation), puis Playwright + axe-core (RGAA). Deux scénarios :
      (1) connexion Keycloak, publication d'une annonce avec photo, annonce visible ; (2) un membre abonné voit la notification ;
    - **après déploiement** : boucle d'attente bornée vérifiant que l'URL publique répond en HTTPS avec le bon SHA.
      Aucun test ni compte de test sur l'environnement public ;
    - charge : JMeter (voir 1.4 point 17).
    - Règle : on construit une fois, on publie sur GHCR exactement les images testées (même digest), seulement si tout est vert.
13. **Sécurité du pipeline (DevSecOps)** [Q3, P1] :

| Étape | Contrôle | Outil | Bloquant |
|---|---|---|---|
| Commit / push | Détection de secrets | gitleaks + push protection GitHub | Oui |
| Build | SAST (analyse statique) | SonarCloud | Oui (Quality Gate) |
| Build | SCA (analyse des dépendances) | Dependabot + Trivy fs | Oui si critique/haute |
| Images | Scan d'images | Trivy image | Oui si critique/haute |
| Stack Compose | DAST (test dynamique) | OWASP ZAP baseline | Non (rapport) |

### 1.4 Déploiement et exploitation [P1, P3, Q3]
14. **Durcissement K3s** :
    - pods non-root, `readOnlyRootFilesystem` (+ `emptyDir` sur `/tmp` pour Spring Boot, image `nginx-unprivileged` pour le front),
      `allowPrivilegeEscalation: false`, `capabilities: drop: ALL` ;
    - NetworkPolicies, ajoutées **en dernier, une par une**, application fonctionnelle : refus par défaut dans `app`, puis Traefik → front/API,
      services → PostgreSQL/RabbitMQ/Keycloak, DNS (CoreDNS), Prometheus → services, `troc-service` → Internet (Cloudinary),
      Flux → GitHub/GHCR ;
    - Traefik seul point d'entrée ; console d'administration Keycloak non publique (`kubectl port-forward`) ;
    - en-têtes de sécurité (HSTS, CSP, `X-Content-Type-Options`) ;
    - rate limiting (décision du 28/10/2026) :
      - appliqué **uniquement à `POST /annonces`** ;
      - mis en place **après la correction de l'IP client** (`externalTrafficPolicy: Local` sur le service Traefik) : sans elle,
        Traefik voit tous les clients sous une seule adresse interne (`10.42.0.1`, expérience 1 partie 3) ;
      - pendant les campagnes JMeter, l'IP de la machine de test est exemptée ;
      - prouvé par un test séparé : envoi rapide de requêtes depuis une même IP, réponse **429** attendue après la limite ;
    - requests/limits sur chaque pod ;
    - pare-feu du VPS : seuls 22, 80, 443 ouverts ; 6443 (API K3s) fermé, `kubectl` via tunnel SSH ; SSH par clé uniquement.
15. **Secrets** : `.env` local (ignoré par git) ; sur le VPS, Secret Kubernetes créé une fois à la main depuis un `.env` jamais commité ;
    les manifests Flux ne référencent que le nom du secret ; jetons CI (SonarCloud, GHCR) dans les secrets GitHub Actions.
16. **Observabilité** [E2, Q1] :
    - métriques : Micrometer (`/actuator/prometheus`, non exposé par l'Ingress), plugin Prometheus de RabbitMQ, kube-state-metrics ;
    - logs JSON sur stdout, `correlationId` créé à l'entrée et propagé dans l'en-tête de `AnnoncePubliee` ;
    - 2 dashboards Grafana : « Santé » (indicateurs 3 et 4, JVM, files/DLQ) et « Montée en charge » (replicas, CPU, latence, file) ;
    - 3 alertes : 5xx > 1 %, DLQ > 0, pod en redémarrage en boucle.
17. **Montée en charge et disponibilité** [P3] :
    - JMeter, utilisateurs authentifiés, ~80 % `GET /annonces`, ~20 % `POST /annonces` avec une photo de test ~200 Ko ;
    - pendant les campagnes : `StockagePhoto=jetable` (valide, ne garde rien, renvoie une fausse URL), désactivé par défaut et vérifié par un test ;
      plus une courte campagne avec le vrai Cloudinary pour mesurer la dépendance ;
    - HPA sur le CPU : `troc-service` 1→3 replicas, `notification-service` 1→2 replicas (budget RAM) ;
    - démonstration : replicas qui montent/descendent, file RabbitMQ qui se vide, suppression d'un pod sous charge (probes readiness/liveness).
18. **Budget RAM cible** (à valider en expérience 1) :

| Composant | Replicas | Limite / pod | Total max |
|---|---|---|---|
| Système + K3s (Traefik, CoreDNS, metrics-server) | — | — | ~600 Mo |
| Keycloak | 1 | 750 Mo | 750 Mo |
| PostgreSQL (2 bases) | 1 | 256 Mo | 256 Mo |
| RabbitMQ | 1 | 256 Mo | 256 Mo |
| troc-service (`MaxRAMPercentage=75`) | 1–3 | 384 Mo | 1152 Mo |
| notification-service | 1–2 | 320 Mo | 640 Mo |
| Front nginx | 1 | 32 Mo | 32 Mo |
| Prometheus + Grafana + kube-state-metrics | 1 | ~400 Mo | 400 Mo |
| Flux | — | ~200 Mo | 200 Mo |
| **Total au pic** | | | **~4,3 Go en limits** (requests plus basses, marge à confirmer) |

    Plan B si le budget ne tient pas : réduire la rétention Prometheus, réduire l'empreinte de Keycloak.
19. **Dépôt** : monorepo (`troc-service/`, `notification-service/`, `front/`, `deploy/`, `e2e/`, `load/`, `docs/`) ;
    trunk-based, PR courtes (une par ticket), `main` protégée (tous les contrôles verts), workflows filtrés par chemin,
    Conventional Commits, lien vers le ticket dans chaque PR, images taguées avec le SHA du commit.

## 2. Backlog (chaque story inclut son écran Angular minimal)

| # | User story | Service | Critères d'acceptation principaux |
|---|---|---|---|
| US1a | En tant que membre, je publie une annonce de troc avec sa photo (de bout en bout, `StockagePhoto` en mémoire) | troc | 201 si valide ; 400 si invalide ; **400 sans photo** ; photo > 2 Mo ou format non autorisé → 400 ; 401 sans jeton ; auteur tiré du JWT ; annonce visible dans la liste |
| US1b | Branchement de Cloudinary derrière `StockagePhoto` | troc | photo stockée, `publicId`/URL enregistrés ; suppression si l'enregistrement échoue ; clé lue depuis le secret |
| US2 | En tant que membre, je gère mes catégories d'intérêt | notification | ajout/retrait ; catégorie inconnue → 400 |
| US3 | En tant que membre, je suis notifié d'une nouvelle annonce | notification | auteur → `CONFIRMATION` ; abonné → `ALERTE` ; non-abonné → rien ; auteur abonné → seulement `CONFIRMATION` ; message en double → une seule notification |
| US4 | En tant que membre, je consulte mes notifications et les marque comme lues | notification | je ne vois que les miennes ; marquer celle d'un autre → 404 |
| US5 | En tant que modérateur, je retire une annonce | troc | modérateur → `RETIREE`, invisible ; `USER` → 403 |

Ordre : US1a → US2 → US3 (tranche « publier → notifier » démontrable), puis US4, US5, US1b.

## 3. Expérimentations en bac à sable [E1]
Une journée maximum chacune ; au-delà, on documente la difficulté et on avance. Un rapport par expérience dans `docs/experimentations/`.
Namespace `sandbox`, supprimé après usage, jamais en même temps que `app` si la RAM ne le permet pas.

1. **Socle K3s** : RAM réelle de chaque composant, Traefik + cert-manager + Let's Encrypt (staging puis prod), Keycloak
   (issuer public / JWKS interne), HPA (metrics-server, passage de 1 à 3 replicas sous charge, RAM consommée), application des NetworkPolicies.
2. **Broker** : RabbitMQ testé complètement (publication/consommation entre pods, confirms, ack, retry, DLQ, perte d'un pod, RAM) ;
   NATS JetStream sur le même scénario simple + mesure RAM, pour justifier le choix.
3. **Livraison continue** : GitHub Actions → GHCR → Flux ; Secret Kubernetes créé à la main et référencé par son nom ;
   mode de suivi des nouvelles images par Flux ; RAM de Flux.
4. **Cloudinary** : upload simple depuis un pod, clé lue dans le Secret.

## 4. Livrables documentaires

| Livrable | Contenu attendu | Source / critère |
|---|---|---|
| Schéma du cycle DevSecOps | Étapes du cycle de vie avec les mesures de sécurité de chacune (tableau 1.3 §13 + durcissement 1.4) | `consignes.md` §1.1.2 — P1, Q3 |
| Schéma détaillé du pipeline CI/CD | Build, tests (unitaires, intégration, acceptation, E2E Compose), contrôles sécurité, publication GHCR, Flux, vérification SHA | `consignes.md` §1.1.2 — P1, Q2 |
| Schéma d'architecture technique | Services, BDD, RabbitMQ, Keycloak, Traefik/TLS, Cloudinary, observabilité, flux et protocoles, frontières de sécurité | `consignes.md` §1.2.1 — E2, O1 |
| Cartographie des compétences | Équipe : architecte (lead dev) + 2 développeur·euse·s juniors ; compétences requises, niveaux, écarts ; au moins une action de formation | `consignes.md` §1.1.3, `contexte.md` §1 — P2 |
| Indicateurs qualité | Tableau 1.3 §11 et lien avec la dette technique | `consignes.md` §1.1.1 — Q1 |
| Backlog | User stories + critères d'acceptation (Gherkin) | `consignes.md` §1.2.1 — E3 |
| Rapports d'expérimentation | Environnement, étapes pour reproduire, difficultés, limites, résultat (adoption/rejet) | `consignes.md` §1.2.2 — E1 |
| ADR | Une décision par fichier dans `docs/adr/`, dont l'écart Cloudinary hors UE | `CLAUDE.md` — E2 |
| Plan de remédiation | Analyse des résultats (tests, Trivy, ZAP, charge), risques priorisés, préconisations (liste §6) | `consignes.md` §1.3 — Q3 |

## 5. Priorisation MoSCoW (quoi abandonner si le temps manque)

**Must** (sans cela, un critère de la grille tombe)
- US1a, US2, US3 (tranche publier → notifier) [E3]
- Communication asynchrone RabbitMQ avec confirms, ack, idempotence [E2]
- Keycloak OIDC + PKCE, resource servers, RBAC côté serveur [Q3]
- HTTPS : Traefik + cert-manager + Let's Encrypt [Q3]
- CI GitHub Actions : tests unitaires + intégration/acceptation (≥ 2 types), SonarCloud, Trivy, gitleaks [P1, Q2]
- Images sur GHCR et déploiement sur K3s (Flux ; repli : déploiement scripté si Flux échoue en expérience 3) [P1, P3]
- Requests/limits, securityContext non-root, secrets hors dépôt
- Prometheus + Grafana, dashboard « Montée en charge » [E2, P3]
- HPA + campagne JMeter + suppression d'un pod sous charge [P3]
- Expérimentations 1, 2 (RabbitMQ) et 3 [E1]
- Tous les livrables documentaires du §4 [P1, P2, Q1, Q3, O1]

**Should** (attendu, mais un repli existe)
- US4, US5
- US1b (Cloudinary) — repli : `StockagePhoto` en mémoire, limite documentée
- E2E Playwright + axe-core dans Compose
- OWASP ZAP baseline, Dependabot
- NetworkPolicies, en-têtes de sécurité, pare-feu VPS (pare-feu et SSH par clé à faire dès l'installation)
- `correlationId` propagé, logs JSON, 3 alertes, dashboard « Santé »
- Vérification post-déploiement du SHA

**Could** (bonus)
- Comparaison NATS (expérience 2 partielle)
- Expérience 4 (Cloudinary depuis un pod) et campagne de charge avec le vrai Cloudinary
- Rate limiting Traefik
- Mode `StockagePhoto=jetable` (sinon, campagne de charge à faible volume)

**Won't (V1)** : voir §6 hors périmètre.

## 6. Hors périmètre V1
- Métier : mots-clés libres, ciblage selon la navigation, lots d'objets, articles recherchés, chat, négociation, modération a priori,
  signalements, mise à jour des notifications d'une annonce retirée.
- Notification : e-mail, push, WebSocket.
- Messagerie : outbox transactionnelle, KEDA.
- Exploitation : Loki, traces distribuées (OpenTelemetry/Tempo), service mesh, clustering PostgreSQL, rollback automatique (Flagger).
- Sécurité : réencodage des photos et suppression EXIF, Sealed Secrets/SOPS, cosign/SBOM, ZAP actif, BFF, mTLS, WAF, Pod Security Admission strict, rotation des secrets.

## 7. Questions ouvertes
- Valeur de N pour l'indicateur 3 : à calibrer lors de la première campagne de charge (expérience 1).
- Mode de suivi des nouvelles images par Flux (image automation ou mise à jour du manifest par la CI) : expérience 3.
- Budget RAM réel et éventuel plan B : expérience 1.

## 8. Liste pour le plan de remédiation
- Photos hébergées hors UE (Cloudinary) — écart assumé pour le POC, à documenter dans un ADR (`contexte.md` §2).
- Métadonnées EXIF (dont GPS) non supprimées des photos (RGPD).
- Dépendance externe Cloudinary : quota, latence, disponibilité.
- Perte possible d'un événement entre commit et publication → outbox transactionnelle.
- Secrets créés à la main → Sealed Secrets, puis rotation automatique.
- WAF, Pod Security Admission strict.
- Audit RGAA manuel (axe-core ne couvre qu'une partie des critères).
- BFF, mTLS, signature d'images (cosign) et SBOM.
