# SQ-03 — Front Angular : page des versions

| | |
|---|---|
| Dépend de | SQ-02 ; ADR 0006 (D1) |
| Sources | `cadrage.md` §1.4.14 (`nginx-unprivileged`, en-têtes de sécurité), §1.4.18 (16/32 Mio), §1.4.19 |

## But

Une page publique qui montre que les trois composants livrés sont bien ceux du dernier commit.

## Contenu

- `front/` : Angular (version stable courante), une page d'accueil qui appelle `/api/troc/version` et
  `/api/notifications/version` (même origine, D1) et affiche aussi la version du front (`/version.json` écrit au build).
  Affiche « indisponible » si un service ne répond pas. Pas encore de connexion (SQ-06).
- Image `nginx-unprivileged` (version épinglée) : build multi-étapes, `readOnlyRootFilesystem` avec `emptyDir` pour
  les répertoires temporaires de nginx ; en-têtes `Content-Security-Policy` (sources limitées à `'self'`),
  `X-Content-Type-Options`, `Referrer-Policy` ; repli `index.html` pour les routes Angular.
- `deploy/app/front/` : Deployment (16/32 Mio), Service, Ingress `/` (mêmes middlewares, même Secret TLS).
- `front.yml` : filtre `front/**` ; `npm ci`, tests unitaires, build, Trivy fs et image, GHCR, tag, vérification de
  `https://epouvante.…/version.json`. Même logique de tag sérialisé que D4.

## Critères d'acceptation

- Tests unitaires (écrits d'abord) : affichage des deux versions ; affichage « indisponible » si une API répond en erreur.
- En ligne : la page affiche les trois SHA ; l'en-tête CSP est présent ; HTTP redirigé vers HTTPS.
- Pod non-root ; RAM mesurée (≤ 32 Mio).
