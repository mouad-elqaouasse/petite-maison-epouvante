# ADR 0015 — Droits de Prometheus : `Role` dans `app`, `ClusterRole` minimal pour cAdvisor

- Statut : acceptée
- Date : 2026-11-03
- Source : ticket SQ-07 (`docs/tickets/SQ-07-observabilite.md`, Décisions 2) ; `docs/cadrage.md` §1.4.16 ;
  exp. 1, partie 2, étape I (`experimentations/exp1/40-prometheus.yaml`) et partie 5, étape A1 bis

## Contexte

- Le ticket SQ-07 demandait des droits limités au namespace `app` (`Role`, pas de `ClusterRole`).
- Les tableaux de bord ont besoin du CPU et de la RAM de chaque conteneur et de la RAM du nœud (« Démo HPA », exp. 1).
  Ces métriques viennent de cAdvisor, intégré au kubelet, lu par le proxy de l'API : `/api/v1/nodes/<nœud>/proxy/metrics/cadvisor`.
- Les nœuds ne sont pas des objets d'un namespace : un `Role` ne peut pas donner accès à `nodes` ni à `nodes/proxy`.
- L'exp. 1 utilisait un `ClusterRole` large (`nodes`, `nodes/proxy`, `nodes/metrics`, `services`, `endpoints`, `pods`
  sur tout le cluster).
- Flux applique `deploy/app/` avec le compte `cluster-admin` (`deploy/flux/10-flux-composants.yaml`) : il peut créer
  un `ClusterRole`.

## Décision

1. **`Role` dans `app`** pour la découverte des pods des services (`pods` : get, list, watch).
2. **`ClusterRole` minimal `app-prometheus-cadvisor`** : `nodes` (list, watch) pour découvrir le nœud,
   `nodes/proxy` (get) pour lire cAdvisor. Rien d'autre hors de `app`.
3. **Séries cAdvisor filtrées à la collecte** (`metric_relabel_configs`) : seules celles du namespace `app`, du cgroup
   racine (`id="/"`) et `machine_memory_bytes` sont gardées, pour ne pas stocker les conteneurs des autres namespaces.
4. **kube-state-metrics limité à `app`** (`--namespaces=app`) et à quatre ressources (pods, Deployments, ReplicaSets,
   HPA) : un `Role` suffit.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Aucun accès à cAdvisor (kube-state-metrics seul) | plus de CPU ni de RAM par conteneur : ni le tableau de bord « Montée en charge », ni le suivi du budget RAM (ADR 0005) |
| metrics-server comme source | expose l'API `metrics.k8s.io` (dernière valeur seulement), pas un format que Prometheus collecte ; demande aussi un `ClusterRole` |
| `ClusterRole` de l'exp. 1 | droits de lecture sur les pods, Services et endpoints de tout le cluster, inutiles ici |
| `resourceNames` limité au nom du nœud | couple le dépôt au nom du VPS (`vps-…`) ; la découverte des nœuds demande de toute façon `list` |

## Conséquences

- `nodes/proxy` en lecture permet aussi de lire les autres points du kubelet du nœud (`/metrics`, `/stats`, `/pods`),
  donc des informations sur les pods des autres namespaces : risque accepté pour un compte de service utilisé par
  Prometheus seul, dans un cluster mono-nœud et mono-application.
- Le `ClusterRole` et le `ClusterRoleBinding` portent le préfixe `app-` : retirés par Flux (`prune: true`) avec le reste
  de `deploy/app/`.
- NetworkPolicies (SQ-08) : Prometheus doit joindre l'API Kubernetes (Service 443 et IP du nœud 6443).
