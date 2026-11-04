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

> **Note pour US1 (ajoutée le 04/11/2026)** : l'envoi des photos vers Cloudinary demandera une règle de sortie pour
> `troc-service` (443 hors cluster, plus le DNS déjà ouvert). Sans elle, le refus par défaut bloque l'appel : la matrice
> le vérifie aujourd'hui (`troc-service -> Internet (avant US1)` : bloqué). Règle à ajouter dans
> `deploy/app/networkpolicies/` avec la user story, et test correspondant à passer à « autorisé » dans
> `outils/sq-08/tester-flux.sh`.

- Script de matrice de flux (adapté de `exp1/98`) : attendu comparé à l'obtenu, avec un pod « intrus ».

## Critères d'acceptation

- Matrice lancée **sans règle** (référence), puis après chaque règle ; résultat final : attendu = obtenu.
- Après `kubectl rollout restart` de tous les Deployments de `app` : page en ligne, connexion, `/moi`, cibles Prometheus UP.
- Le pod intrus ne joint ni PostgreSQL, ni RabbitMQ, ni Keycloak.
- Renouvellement Let's Encrypt non gêné (HTTP-01 passe par Traefik et cert-manager, hors de `app` : à vérifier).

## Vérification (04/11/2026)

Outils : `outils/sq-08/` (matrice `tester-flux.sh` sur des pods « sondes » busybox portant le label de chaque
composant, car les images des services n'ont ni shell ni `nc` ; pod intrus `intrus.sh`). Résultats :
`docs/soutenance/captures/sq-08/`. Décisions : ADR 0017.

- **Durcissement des pods** (PR à part, fusionnée avant les règles) : `enableServiceLinks: false` sur les 9 Deployments,
  ServiceAccount `default` sans jeton. Appliqué Deployment par Deployment, Flux suspendu (RAM jamais sous 693 Mio),
  puis Flux relancé sans aucun pod recréé.
- **Chemin des clés de Keycloak** : adresse interne `http://keycloak:8080/.../certs` dans les deux services (issuer
  public seulement comparé) : la règle `services-keycloak` vise Keycloak:8080.
- **Matrice** de 35 tests (29 stricts, 6 sur le chemin réel) : référence sans règle, 0 écart ; puis 0 écart après chacune
  des 8 étapes (18 NetworkPolicies, 9 règles). Écart au ticket : pas de règle vers le Service 443 de l'API (sans effet,
  exp. 1, B6) ; ajout du solveur ACME (8089) dans `traefik-entree`.
- **Redémarrage** de chaque pod par `kubectl delete pod` (pas `rollout restart`, annulé par Flux), Keycloak en premier,
  matrice après chacun : 0 écart, RAM jamais sous 835 Mio. Tunnels Grafana et Prometheus : 200. Connexion PKCE et `/moi`
  vérifiées par le Lead Developer. 8 cibles Prometheus UP.
- **Pod intrus** (label du front) : avant, il joint PostgreSQL, RabbitMQ, Keycloak, `troc-service:8081`, Prometheus et
  Internet, reçoit un jeton et ≈ 100 variables de Services ; après, seul le DNS répond, aucun jeton, seules les variables
  `KUBERNETES_*` restent (injectées par le kubelet quel que soit `enableServiceLinks`).
- Renouvellement Let's Encrypt : règle `traefik-entree-solveur-acme` en place ; non observé (premier renouvellement
  le 2026-12-27).

### Coupure pendant la mise en place

- Le site public (`epouvante.` et `auth.`) a été **coupé environ 1 minute** (13:22 à 13:23 UTC) : entre l'étape 1
  (`refus-total` et `sortie-dns`) et l'étape 2 (`traefik-entree`), toute nouvelle connexion de Traefik vers les pods
  était refusée. Coupure voulue : le critère d'acceptation impose une matrice après chaque règle, à partir du refus total.
  Les services internes ont tenu grâce aux connexions déjà ouvertes (exp. 1, B2), puis aux règles suivantes.
- **En production**, on éviterait la coupure en inversant l'ordre : **règles d'autorisation d'abord, refus total en
  dernier**. Une règle qui sélectionne un pod l'isole déjà dans le sens qu'elle déclare, donc chaque pod doit recevoir
  toutes ses règles (entrée et sortie, DNS compris) dans le même `kubectl apply` ; quand tous les flux sont couverts,
  le refus total ne change plus rien aux flux légitimes et ne bloque que le reste. Le tout est validé d'abord sur un
  environnement de préproduction avec la même matrice, et la règle de Traefik n'est jamais absente.
