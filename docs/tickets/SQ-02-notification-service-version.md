# SQ-02 — `notification-service` sur la même chaîne

| | |
|---|---|
| Dépend de | SQ-01 |
| Sources | `cadrage.md` §1.4.17 (1 replica fixe), §1.4.18 (256/320 Mio), §1.4.19 ; ADR 0005 |

## But

Prouver que la chaîne est réutilisable : un deuxième service livré avec le même workflow, sans copier-coller de pipeline.

## Contenu

- `notification-service/` : même squelette que SQ-01, `GET /api/notifications/version`, actuator sur 8081.
- `deploy/app/notification-service/` : Deployment **1 replica fixe** (256/320 Mio), même durcissement et mêmes sondes ;
  Service ; Ingress `/api/notifications` (mêmes middlewares, même Secret TLS).
- `notification-service.yml` : filtre `notification-service/**`, appelle `_service-java.yml`.
- Si nécessaire : facteurs communs remontés dans le workflow réutilisable (aucune logique propre à un service dedans).

## Critères d'acceptation

- Test (écrit d'abord) : `/api/notifications/version` renvoie le SHA.
- Un commit sur `notification-service/` seul ne relance pas `troc-service`, et inversement.
- Deux commits poussés presque en même temps (un par service) : les deux tags sont écrits dans `deploy/`, sans échec de push.
- Les deux SHA vérifiés en ligne ; RAM du pod mesurée.

## Résultats (30/10/2026) — ticket clos

- **Local** : `VersionTest` repris de SQ-01 (3 tests : version, actuator absent de 8080, sondes et métriques sur 8081),
  en échec (404 sur `/api/notifications/version`) puis vert (`mvnw verify`, JDK 25 en conteneur).
- **Réutilisation** : `notification-service.yml` ne diffère de `troc-service.yml` que par le nom, le filtre et l'URL ;
  `_service-java.yml` inchangé (aucun facteur commun à remonter). Manifests copiés de troc-service (kustomize : rendu valide).
- **Première livraison** : fusion de la PR #6 (`b526c34`) → seul le workflow `notification-service` lancé ; commit du
  bot `66e9376` ; SHA en ligne **117 s** après le commit du tag ; HTTP → 301 ; `/api/notifications/actuator/health` → 404.
- **Filtres par chemin** : la PR #7 (`troc-service/` seul) n'a lancé que `troc-service`, la PR #8
  (`notification-service/` seul) que `notification-service` ; aucun workflow relancé par les commits du bot.
- **Deux livraisons rapprochées** : PR #8 et #7 fusionnées à 14 s d'écart (15:26:33 et 15:26:47 UTC) → deux runs verts,
  deux commits de tag (`7e359b5` notification-service `0f7233f`, puis `d71794f` troc-service `9c3f132`), **aucun échec
  de push** ; les deux SHA en ligne (86 s et 116 s après leur commit de tag) ; Kustomization `app` `Ready=True` sur `d71794f`.
- **Écart** : la protection (groupe `deploy-tag` sérialisé + `git pull --rebase`, ADR 0009) est en place mais **n'a pas
  pu être observée** : les constructions ont duré 2 min 00 et 2 min 12, si bien que le premier job `tag` s'est terminé
  (15:28:48) avant le démarrage du second (15:29:07), sur un `main` qui contenait déjà le premier tag. Ni attente dans le
  groupe ni rattrapage par rebase. Le test n'a pas été répété (décision du 30/10/2026) ; la limite du groupe à un seul
  job en attente est notée dans SQ-03.
- **Pod** : 1 replica, `Running`, UID 65532, 0 redémarrage.
- **RAM (`kubectl top`, ≈ 3 min après le démarrage)** : `notification-service` **131 Mio** (limite 320 Mio, estimation
  250 Mio, ADR 0005) ; `troc-service` 138 Mio ; nœud 2 035 Mio (53 %) ; RAM disponible 1 976 Mio (`free -m`, contre
  2 055 après SQ-01).
