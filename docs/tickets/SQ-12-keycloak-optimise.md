# SQ-12 — Image Keycloak optimisée (bonus)

| | |
|---|---|
| Dépend de | SQ-06 (Keycloak standard en place) |
| Priorité | **Could** : seulement s'il reste du temps (décision du 30/10/2026) |
| Sources | ADR 0003 (Conséquences) ; rapport exp. 1 §7 (pic de mémoire à la limite de 768 Mio, un cœur occupé au démarrage) |

## But

Réduire le pic de mémoire et la durée du démarrage de Keycloak en faisant l'étape de build une fois, dans la CI.

## Contenu

- `keycloak/Dockerfile` : image officielle (même version), `kc.sh build` avec les options figées (base PostgreSQL,
  santé, métriques), puis `start --optimized`.
- `keycloak.yml` : filtre `keycloak/**` ; build, Trivy image, GHCR, tag, vérification de
  `https://auth.…/realms/epouvante/.well-known/openid-configuration`.
- Mesure avant / après : pic de mémoire au démarrage, durée jusqu'à `Ready`, mémoire au repos.

## Critères d'acceptation

- Keycloak démarre avec l'image optimisée ; connexion PKCE et `/moi` toujours fonctionnels.
- Mesures avant / après consignées ; si aucun gain significatif, retour à l'image standard (et constat documenté).
- Si adoptée : ADR rédigée.

## À faire par toi

Rendre public le paquet GHCR `keycloak` après le premier push.
