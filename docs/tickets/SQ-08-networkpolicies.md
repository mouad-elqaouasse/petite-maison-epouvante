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
| prometheus-cibles | Prometheus → ports de collecte (détail ci-dessous) |
| grafana-prometheus | Grafana → Prometheus 9090 |
| sortie-api-k8s | Prometheus, kube-state-metrics → API Kubernetes (Service 443 **et** IP du nœud 6443) |

Ports à ouvrir pour Prometheus (configuration de SQ-07, `deploy/app/prometheus/prometheus.yml`) :

| Source | Destination | Port TCP | Usage |
|---|---|---|---|
| Prometheus | `troc-service`, `notification-service` (chaque pod) | 8081 | `/actuator/prometheus` (port de gestion, ADR 0007) |
| Prometheus | RabbitMQ | 15692 | `/metrics` et `/metrics/detailed` (messages par file, DLQ) |
| Prometheus | Keycloak | 9000 | `/metrics` |
| Prometheus | kube-state-metrics | 8080 | `/metrics` |
| Prometheus | API Kubernetes : Service `kubernetes.default` | 443 | découverte des pods et des nœuds |
| Prometheus | API Kubernetes : IP du nœud | 6443 | même flux après traduction d'adresse (exp. 1, partie 5, B6) |
| Prometheus | kubelet : IP du nœud | 10250 | cAdvisor `/metrics/cadvisor`, lu directement (ADR 0016, ajouté le 03/11/2026) |
| kube-state-metrics | API Kubernetes (Service 443 et IP du nœud 6443) | 443, 6443 | lecture des pods, Deployments, ReplicaSets, HPA de `app` |
| Grafana | Prometheus | 9090 | source de données |

Le tunnel `kubectl port-forward` vers Grafana (3000) et Prometheus (9090) ne passe pas par le réseau des pods : à vérifier
après le refus total. Aucune sortie vers Internet pour Grafana (plugins préinstallés et vérifications de mises à jour désactivés).

Pas de sortie vers Internet dans cette phase : la règle `troc-service` → 443 hors cluster arrive avec US1b (Cloudinary).
Les règles de `flux-system` (exp. 3) restent inchangées.

- Script de matrice de flux (adapté de `exp1/98`) : attendu comparé à l'obtenu, avec un pod « intrus ».

## Critères d'acceptation

- Matrice lancée **sans règle** (référence), puis après chaque règle ; résultat final : attendu = obtenu.
- Après `kubectl rollout restart` de tous les Deployments de `app` : page en ligne, connexion, `/moi`, cibles Prometheus UP.
- Le pod intrus ne joint ni PostgreSQL, ni RabbitMQ, ni Keycloak.
- Renouvellement Let's Encrypt non gêné (HTTP-01 passe par Traefik et cert-manager, hors de `app` : à vérifier).
