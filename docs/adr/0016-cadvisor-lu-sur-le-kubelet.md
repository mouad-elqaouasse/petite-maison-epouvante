# ADR 0016 — cAdvisor lu directement sur le kubelet (`nodes/metrics` au lieu de `nodes/proxy`)

- Statut : acceptée
- Date : 2026-11-03
- Remplace : point 2 de la décision de l'ADR 0015 (le reste de l'ADR 0015 reste valable)
- Source : ticket SQ-10 (`docs/tickets/SQ-10-codeql-dependabot.md`), Trivy config règle KSV-0047 (HIGH) sur
  `deploy/app/prometheus/prometheus.yaml` ; `docs/cadrage.md` §1.3.13 (Trivy config bloquant si critique ou haute)

## Contexte

- L'ADR 0015 donnait à Prometheus `nodes/proxy` (get) pour lire cAdvisor par le proxy de l'API
  (`/api/v1/nodes/<nœud>/proxy/metrics/cadvisor`). Ses Conséquences n'y voyaient qu'un risque de lecture.
- `nodes/proxy` ouvre en réalité toute l'API du kubelet, y compris l'exécution de commandes dans les pods de tous les
  namespaces : Trivy config le classe HIGH (KSV-0047), ce qui bloque désormais la CI (SQ-10).
- Le kubelet autorise `/metrics/cadvisor` avec le sous-droit `nodes/metrics`, qui ne donne accès qu'aux métriques.
- Vérifié sur le VPS le 03/11/2026 (lecture seule) : le certificat du kubelet (port 10250) est signé par
  `k3s-server-ca`, la même CA que le `ca.crt` des comptes de service, et contient l'IP du nœud dans ses SAN.
  metrics-server joint déjà le kubelet depuis un pod (`kubectl top`) : le chemin réseau pod → nœud:10250 existe.

## Décision

1. Prometheus lit cAdvisor **directement sur le kubelet** : découverte `role: node` (adresse par défaut
   `<IP du nœud>:10250`), `metrics_path: /metrics/cadvisor`, TLS vérifié avec le `ca.crt` du compte de service.
2. Le `ClusterRole` `app-prometheus-cadvisor` donne `nodes` (list, watch) et **`nodes/metrics` (get)**,
   plus `nodes/proxy`.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Garder `nodes/proxy` avec une exception Trivy | droit d'exécution dans tous les pods du nœud pour un simple collecteur de métriques |
| `insecure_skip_verify` vers le kubelet | inutile : le certificat est vérifiable avec la CA du cluster |

## Conséquences

- Le label `instance` de la cible `kubernetes-cadvisor` devient `<IP du nœud>:10250` (aucun tableau de bord ni script
  ne filtre dessus).
- NetworkPolicies (SQ-08) : Prometheus doit aussi joindre l'IP du nœud sur le port **10250**.
- Vérification après déploiement : cible `kubernetes-cadvisor` UP (`deploy/scripts/observabilite-verifier.sh`).
