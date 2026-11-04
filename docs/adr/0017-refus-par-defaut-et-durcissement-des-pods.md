# ADR 0017 — Refus par défaut dans `app` et durcissement des pods

- Statut : acceptée
- Date : 2026-11-04
- Complète : ADR 0004 ; **remplace son point 2** (règle API en deux parties)
- Source : ticket SQ-08 (`docs/tickets/SQ-08-networkpolicies.md`) ; `docs/cadrage.md` §1.4.14 ; exp. 1, journal partie 5, B6

## Contexte

- Avant SQ-08, un pod quelconque de `app` (capture `docs/soutenance/captures/sq-08/avant.txt`) recevait un jeton de
  compte de service, voyait l'adresse et le port de chaque Service dans ses variables d'environnement, joignait
  PostgreSQL, RabbitMQ, Keycloak, Prometheus, le port de gestion des services et Internet.
- L'expérience 1 (B6) a montré que la règle vers l'adresse du Service `kubernetes.default` (443) est sans effet : la règle
  s'applique après la traduction d'adresse, seule l'IP du nœud (6443) compte.

## Décision

1. **Refus par défaut** dans `app` (`deploy/app/networkpolicies/`, une règle par fichier, gérées par Flux) : refus total,
   puis DNS, Traefik → ports applicatifs (et solveur ACME), services → PostgreSQL, RabbitMQ et Keycloak (JWKS interne),
   Prometheus → cibles (dont le kubelet, IP du nœud:10250), Grafana → Prometheus.
2. Sortie vers l'API Kubernetes (Prometheus, kube-state-metrics) : **IP du nœud, port 6443 seulement**.
3. **Durcissement des pods** : `enableServiceLinks: false` sur tous les Deployments ; `automountServiceAccountToken: false`
   sur chaque pod qui n'utilise pas l'API **et** sur le ServiceAccount `default` de `app` (un pod qui oublie le réglage ne
   reçoit pas de jeton). Seuls Prometheus et kube-state-metrics, avec leur propre ServiceAccount, gardent un jeton.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Durcissement des pods sans NetworkPolicies | un pod compromis joint toujours la base et Internet |
| Garder la règle vers le Service 443 (ADR 0004, point 2) | sans effet mesuré (exp. 1, B6) |
| Laisser les service links | aucun usage dans le code ; carte des Services offerte à tout pod compromis |

## Conséquences

- Tout nouveau flux (US1 : `troc-service` → Cloudinary) demande une règle et une ligne dans `outils/sq-08/tester-flux.sh`.
- Les variables `KUBERNETES_SERVICE_*` restent injectées par le kubelet malgré `enableServiceLinks: false` (constaté) :
  elles ne donnent que l'adresse de l'API, inutilisable sans jeton.
- Un pod neuf subit environ 1 s de refus total : les services réessaient leurs connexions (ADR 0013).
- Mise en place sans coupure en production : règles d'autorisation d'abord, refus total en dernier (SQ-08, Vérification).
