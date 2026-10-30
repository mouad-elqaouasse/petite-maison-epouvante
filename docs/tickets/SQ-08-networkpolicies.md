# SQ-08 — NetworkPolicies

| | |
|---|---|
| Dépend de | SQ-06, SQ-07 (application complète et fonctionnelle) |
| Sources | `cadrage.md` §1.4.14 ; ADR 0004 ; rapport exp. 1 §6 ; outils `experimentations/exp1/90` à `98` |

## But

Refus par défaut dans `app`, puis chaque flux nécessaire ouvert explicitement et vérifié.

## Contenu

`deploy/app/networkpolicies/`, une règle par fichier, ajoutées **une par une** :

| Règle | Flux |
|---|---|
| refus-total | tout le trafic entrant et sortant de `app` |
| sortie-dns | tous les pods → CoreDNS (`kube-system`, 53 UDP/TCP) |
| traefik-entree | Traefik (`kube-system`) → front, `troc-service`, `notification-service`, Keycloak (ports applicatifs seulement) |
| services-postgresql | `troc-service`, `notification-service`, Keycloak → PostgreSQL 5432 |
| services-rabbitmq | `troc-service`, `notification-service` → RabbitMQ 5672 |
| services-keycloak | `troc-service`, `notification-service` → Keycloak (JWKS, port HTTP interne) |
| prometheus-cibles | Prometheus → 8081 des services, 15692 RabbitMQ, kube-state-metrics, Keycloak |
| grafana-prometheus | Grafana → Prometheus |
| sortie-api-k8s | Prometheus, kube-state-metrics → API Kubernetes (Service 443 **et** IP du nœud 6443) |

Pas de sortie vers Internet dans cette phase : la règle `troc-service` → 443 hors cluster arrive avec US1b (Cloudinary).
Les règles de `flux-system` (exp. 3) restent inchangées.

- Script de matrice de flux (adapté de `exp1/98`) : attendu comparé à l'obtenu, avec un pod « intrus ».

## Critères d'acceptation

- Matrice lancée **sans règle** (référence), puis après chaque règle ; résultat final : attendu = obtenu.
- Après `kubectl rollout restart` de tous les Deployments de `app` : page en ligne, connexion, `/moi`, cibles Prometheus UP.
- Le pod intrus ne joint ni PostgreSQL, ni RabbitMQ, ni Keycloak.
- Renouvellement Let's Encrypt non gêné (HTTP-01 passe par Traefik et cert-manager, hors de `app` : à vérifier).
