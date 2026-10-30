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
