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

## Limite à régler : un seul job en attente dans le groupe `deploy-tag` (relevée en SQ-02)

GitHub Actions garde au plus **un job en cours et un job en attente** par groupe de concurrence ; un nouveau job en
attente annule celui qui attendait déjà, même avec `cancel-in-progress: false`. Avec deux composants (SQ-02), cela suffit.
Avec le front comme troisième composant livré, trois livraisons rapprochées peuvent annuler un job `tag` : le SHA de ce
composant n'est alors jamais écrit dans `deploy/` (et sa vérification en ligne ne tourne pas).
Solution à proposer et faire valider (ADR 0009 à compléter) avant de brancher `front.yml` sur le job de tag.

## Critères d'acceptation

- Tests unitaires (écrits d'abord) : affichage des deux versions ; affichage « indisponible » si une API répond en erreur.
- En ligne : la page affiche les trois SHA ; l'en-tête CSP est présent ; HTTP redirigé vers HTTPS.
- Pod non-root ; RAM mesurée (≤ 32 Mio).

## Faille détectée par Trivy dans l'image de base (30/10/2026) — exemple pour le plan de remédiation

- **Détection** : PR #10, job « Trivy image (bloquant si critique ou haute) » du workflow `front` en échec (run
  `41088284637`). Image construite sur `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` (Alpine 3.24.2), déjà la plus
  récente : aucun nouveau tag ni digest depuis le 19/10/2026.
- **Failles** :

| CVE | Gravité | Paquet (installé → corrigé) | Nature |
|---|---|---|---|
| CVE-2026-111497 | critique | `brotli-libs` 1.2.0-r1 → 1.2.1-r0 | écriture hors limites dans le décodeur Brotli |
| CVE-2026-92392 | haute | `curl`, `libcurl` 8.22.0-r0 → 8.23.0-r0 | utilisation après libération (envoi HTTP/2) |

- **Analyse** : correctifs déjà publiés dans le dépôt Alpine 3.24, pas encore dans l'image nginx ; `curl` n'est utilisé
  ni par nginx ni par les scripts de démarrage (`libcurl` n'est requis que par `curl`) ; `brotli-libs` reste requis
  par `freetype`. Pas d'exception `.trivyignore` : une correction existait.
- **Correction** (commit `80b2d3c`, `front/Dockerfile`) : `apk upgrade --no-cache` puis `apk del --no-cache curl`,
  exécutés en root, l'image repassant ensuite en UID 101. Résultat local : Trivy 0.76.1 à 0 faille critique ou haute,
  conteneur fonctionnel en lecture seule.
- **Compromis sur la reproductibilité** : l'image ne dépend plus seulement du tag épinglé, mais aussi du jour du build
  (paquets Alpine du moment). Deux builds du même commit peuvent différer. Accepté pour le POC : chaque build récupère
  les correctifs de sécurité sans attendre l'image amont, et Trivy reste bloquant en cas de régression. Alternative
  écartée : mise à jour ciblée des seuls paquets en cause, plus reproductible mais à refaire à chaque nouvelle CVE.
- **Pour le plan de remédiation** (`docs/cadrage.md` §8) : épingler l'image de base par digest et confier sa mise à
  jour à Dependabot (ou Renovate) pour retrouver des builds reproductibles ; scan Trivy planifié (quotidien) des images
  déjà déployées, et pas seulement à la construction ; SBOM et signature des images (déjà listés au §8).
