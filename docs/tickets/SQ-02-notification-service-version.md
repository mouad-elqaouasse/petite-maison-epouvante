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
