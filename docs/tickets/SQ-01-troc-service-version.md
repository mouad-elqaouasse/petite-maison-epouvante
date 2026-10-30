# SQ-01 — Premier fil : `troc-service` /version livré en HTTPS

| | |
|---|---|
| Dépend de | SQ-00 ; ADR 0006 (D1), 0007 (D2), 0009 (D4) |
| Sources | `cadrage.md` §1.3.12 (règle « construire une fois »), §1.3.13, §1.4.14, §1.4.19, §1.4.20 ; ADR 0005 ; rapport exp. 3 §8-9 |

## But

Le fil le plus court de bout en bout : un commit sur `troc-service/` arrive en ligne, en HTTPS, sans geste manuel.

## Contenu

- **Service** : `troc-service/` (Spring Boot 4.1, Java 25, `mvnw`). `GET /api/troc/version` → `{ "service": "troc-service", "version": "<SHA>" }`
  (SHA passé au build). Actuator sur le port 8081 (D2) : `health` (groupes `liveness`/`readiness`) et `prometheus`.
- **Image** : distroless Java 25 non-root (exp. 3), `-XX:MaxRAMPercentage=75`.
- **Manifests `deploy/app/troc-service/`** : Deployment 1 replica (requests/limits 256/384 Mio), securityContext
  (non-root, `readOnlyRootFilesystem`, `emptyDir` sur `/tmp`, `allowPrivilegeEscalation: false`, `drop: ALL`),
  `startupProbe` pour ≈ 30 s de démarrage, readiness/liveness ; Service ; Ingress `epouvante.…` `/api/troc`
  avec `secretName: epouvante-tls` et l'annotation `letsencrypt-prod`.
- **Middlewares Traefik communs** (`deploy/app/traefik/`) : redirection HTTP → HTTPS, HSTS, `X-Content-Type-Options: nosniff`.
  Vérifier que la redirection laisse passer la validation HTTP-01 (ADR 0001, Conséquences).
- **CI** (D4) :
  - `gitleaks.yml` : sur chaque push et PR, tout l'historique, bloquant ;
  - `_service-java.yml` (réutilisable) : `mvnw verify` → Trivy fs (dépendances) → build de l'image → Trivy image
    (critique/haute bloquant) → push GHCR taggé SHA (sur `main` seulement, même image que testée) →
    job sérialisé qui écrit le tag dans `deploy/app/troc-service/kustomization.yaml` et le commite (`github-actions[bot]`) →
    job de vérification : boucle bornée jusqu'à ce que `https://epouvante.…/api/troc/version` renvoie le SHA ;
  - `troc-service.yml` : filtre `troc-service/**` et le workflow réutilisable ; actions et version de Trivy épinglées.
- Suppression du workflow `exp3-hello.yml` (l'application de l'exp. 3 n'est plus déployée).

## Critères d'acceptation

- Test (écrit d'abord) : `GET /api/troc/version` renvoie 200 et le SHA configuré.
- `curl -I http://epouvante.…/api/troc/version` → redirection vers HTTPS ; la réponse HTTPS porte `Strict-Transport-Security`.
- `/actuator/*` inaccessible depuis Internet (404).
- Un commit sur `troc-service/` → SHA visible en ligne, sans intervention ; un commit ailleurs ne lance pas ce workflow.
- Un secret factice dans une PR fait échouer gitleaks (testé sur une branche jetable, non fusionnée).
- Pod non-root, 0 redémarrage ; RAM mesurée du pod au repos (première mesure réelle d'une JVM Spring Boot, ADR 0005).
- Le certificat existant est réutilisé (aucune nouvelle `Order`).

## Avancement

- **Local (30/10/2026)** : tests écrits d'abord (3 tests : version, actuator absent de 8080, sondes et métriques
  sur 8081), en échec (404) puis verts (`mvnw verify`, JDK 25 en conteneur, pas de JDK 25 sur le poste).
  Image lancée en lecture seule, UID 65532, `--cap-drop ALL`, 384 Mio : démarrage en 2,5 s, **138 Mio au repos**
  (Docker Desktop, à remesurer sur le VPS). Trivy image et Trivy fs : 0 vulnérabilité critique/haute.
  gitleaks 8.30.1 sur tout l'historique (48 commits) : aucune fuite. actionlint : aucune erreur.
  `kubectl apply --dry-run=server` des manifests de `deploy/app/` : accepté.
- **Trivy fs en CI** : `TRIVY_OFFLINE_SCAN=true`, dépendances lues dans `~/.m2` rempli par le build ; sans cela,
  Maven Central répond 429 (constaté sur le poste).
- **Gitleaks** : binaire officiel épinglé (8.30.1) et vérifié par checksum, plutôt que l'action (décision du 30/10/2026).
- **VPS avant déploiement** : aucun `Certificate` ni `Order` ; RAM disponible 2 299 Mio (`free -m`).
- **Premier déploiement** : le tag initial `aucune-image` laisse le pod en `ImagePullBackOff` jusqu'au premier commit
  du tag ; le premier run échouera à la vérification tant que le paquet GHCR `troc-service` reste privé (prévu).
- **Test gitleaks (30/10/2026)** : branche jetable `test-gitleaks`, secret générique factice (`api_secret`, valeur
  aléatoire, aucun format de fournisseur pour ne rien signaler à un tiers), détecté d'abord en local par la règle
  `generic-api-key`. gitleaks en échec sur le push et sur la PR #4 (valeur masquée dans les logs) ; PR fermée sans
  fusion, branche supprimée.
- **Première livraison (30/10/2026)** : fusion de la PR #3 (`eff0af1`) → run `troc-service` vert en 4 min 24 s
  (construire, tag, vérifier) ; commit du bot `a33c8eb` ; SHA en ligne **107 s** après le commit du tag.
  Le paquet GHCR était déjà téléchargeable sans clé : la vérification n'a pas échoué au premier run (contrairement à
  ce qui était prévu).
- **Critères vérifiés en ligne** :
  - `GET https://epouvante.…/api/troc/version` → `{"service":"troc-service","version":"eff0af1…"}` ;
  - HTTP → redirection vers HTTPS (308 sur `HEAD`, 301 sur `GET`) ; HTTPS : `Strict-Transport-Security: max-age=31536000`
    et `X-Content-Type-Options: nosniff` ;
  - `/actuator/health`, `/actuator/prometheus`, `/api/troc/actuator/health` → 404 ;
  - pod `Running`, UID 65532, **0 redémarrage** ; Kustomization `app` `Ready=True` sur `a33c8eb` ;
  - certificat réutilisé : `Certificate` `epouvante-tls` créé `Ready=True`, **aucune `CertificateRequest` ni `Order`**,
    expiration inchangée (26/01/2027) ;
  - seuls les commits touchant `troc-service/` ont lancé le workflow : ni le commit du bot (`deploy/`), ni la PR #4
    (fichier à la racine), ni ce commit de documentation.
- **RAM (`kubectl top`, ≈ 2 min après le démarrage)** : `troc-service` **166 Mio** au repos (limite 384 Mio), première
  mesure réelle d'une JVM Spring Boot, sous l'estimation de 250 Mio par replica (ADR 0005) ; nœud 1 959 Mio (51 %) ;
  RAM disponible 2 055 Mio (`free -m`, contre 2 299 avant).
