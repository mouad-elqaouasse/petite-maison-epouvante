# ADR 0028 — Grafana en lecture anonyme (Viewer), joignable seulement par le tunnel

- Statut : acceptée
- Date : 2026-11-24
- Source : décision du Lead Developer du 24/11/2026 (captures des campagnes de charge) ; ticket SQ-07 ; ADR 0004, 0008

## Contexte

- Les captures Grafana doivent être prises juste après chaque campagne de charge : l'historique de Prometheus est en
  `emptyDir`, perdu à la recréation du pod (`CLAUDE.md`, Méthode de travail ; ticket SQ-07, Limites).
- Les captures sont automatisées (`load/captures-grafana.sh`, Playwright). Avec la seule connexion administrateur, le
  script devait lire le mot de passe du Secret `grafana-admin` et l'envoyer à chaque capture.
- Grafana n'est pas exposé : Service `ClusterIP`, aucun Ingress ni IngressRoute, aucune NetworkPolicy n'autorise
  d'entrée vers son pod (refus par défaut, ADR 0004). Seul `kubectl port-forward` l'atteint, par le nœud (vérifié le
  24/11/2026 dans le dépôt et sur le cluster).
- Ouvrir ce tunnel demande un accès SSH au VPS et le kubeconfig de `ubuntu`, qui donnent déjà accès à tous les Secrets
  de `app`.

## Décision

1. **Accès anonyme activé avec le rôle Viewer** : `GF_AUTH_ANONYMOUS_ENABLED=true`, `GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer`
   (`deploy/app/grafana/grafana.yaml`).
2. **Le compte administrateur reste inchangé** : son mot de passe, dans le Secret `grafana-admin`, sert seulement à
   l'administration.
3. Les captures (`load/captures-grafana.sh`) passent par le tunnel **sans aucun mot de passe**.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Mot de passe administrateur lu par le script | secret manipulé à chaque capture, et droits d'administration pour une simple lecture |
| Compte de service Grafana avec un jeton | un secret de plus à créer, ranger et renouveler, pour le même accès en lecture |
| Captures à la main dans le navigateur | lent et non reproductible ; les captures sont à refaire après C1, C2 et C3 |

## Conséquences

- **Aucune nouvelle exposition** : la condition d'accès reste le tunnel SSH. Quiconque peut l'ouvrir lit déjà les
  Secrets de `app`.
- Le rôle Viewer peut lire tous les tableaux de bord et interroger la source Prometheus, mais ne peut rien modifier.
  Les tableaux de bord étaient déjà en lecture seule (provisionnés depuis le dépôt).
- **Condition de validité** : si Grafana devient un jour joignable autrement (Ingress, NodePort, NetworkPolicy
  d'entrée), cette décision doit être revue d'abord : l'accès anonyme deviendrait public.
- À l'application, Flux recrée le pod Grafana, ce qui le rend indisponible environ 30 s. Prometheus n'est pas touché :
  son historique est conservé.
