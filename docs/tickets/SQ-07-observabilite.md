# SQ-07 — Prometheus, kube-state-metrics et Grafana

| | |
|---|---|
| Dépend de | SQ-05 (cible RabbitMQ) ; SQ-02 |
| Sources | `cadrage.md` §1.4.16 (stack légère, plugins Grafana désactivés, replicas lus dans kube-state-metrics), §1.4.18 ; rapport exp. 1 §8-9 |

## But

Toutes les métriques du squelette sont collectées et visibles dans Grafana, par tunnel.

## Contenu

- `deploy/app/prometheus/` : Prometheus (version de l'exp. 1), 64/256 Mio, rétention courte (à fixer), découverte des
  pods de `app` par annotations ; droits limités au namespace (`Role`, pas de `ClusterRole`).
  Cibles : actuator des deux services (8081), RabbitMQ (15692), kube-state-metrics, Keycloak, Prometheus lui-même.
- `deploy/app/kube-state-metrics/` : 32/64 Mio, limité au namespace `app`.
- `deploy/app/grafana/` : Grafana (version de l'exp. 1), 128/256 Mio, plugins préinstallés désactivés, source Prometheus
  et tableau de bord « Squelette » (cibles UP, mémoire JVM, replicas par kube-state-metrics) provisionnés depuis le dépôt ;
  mot de passe administrateur généré sur le VPS dans un Secret, jamais affiché (tu le lis toi-même) ; non exposé par l'Ingress (`ssh -t` + `kubectl port-forward`).
- Les dashboards « Santé » et « Montée en charge » et les 3 alertes restent pour une phase suivante.

## Critères d'acceptation

- Toutes les cibles `UP` dans Prometheus.
- Le tableau de bord « Squelette » s'affiche après redémarrage de Grafana, sans configuration manuelle.
- `/actuator/prometheus` toujours inaccessible depuis Internet.
- **Bilan RAM de toute la stack au repos**, comparé au budget de `cadrage.md` §1.4.18 et consigné dans ce ticket.
