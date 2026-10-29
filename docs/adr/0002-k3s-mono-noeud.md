# ADR 0002 — K3s mono-nœud sur le VPS comme plateforme d'exécution

- Statut : acceptée
- Date : 2026-10-29
- Source des mesures : `docs/experimentations/exp1-socle-k3s-rapport.md` (parties 1, 2 et 5)

## Contexte

- Le sujet demande « une gestion du déploiement via un orchestrateur ou un fournisseur cloud » (`docs/sujet/consignes.md`,
  « 1. Travail demandé ») et un POC qui « s'exécute dans un environnement managé » avec montée en charge démontrée
  (`docs/sujet/grille.md`, critère P3).
- Ressources disponibles : un VPS de 4 Go (3,7 Gio utilisables), 2 vCPU, en Europe.
- Expérience 1 : K3s v1.36.5+k3s1 coûte ≈ 650 Mio à vide ; nœud stable (0 redémarrage) ; HPA, NetworkPolicies, Ingress et
  TLS fonctionnent sans composant ajouté (Traefik, ServiceLB, metrics-server, kube-router intégrés).

## Décision

L'environnement `app` tourne sur **K3s mono-nœud** sur le VPS, avec ses composants intégrés : Traefik comme seul point
d'entrée, ServiceLB, CoreDNS, metrics-server, kube-router pour les NetworkPolicies. L'API Kubernetes (6443) reste fermée
sur Internet ; `kubectl` passe par SSH. Le namespace `sandbox` et `app` ne tournent jamais en même temps si la RAM ne le
permet pas.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Kubernetes complet (kubeadm) | plus lourd en RAM, Ingress et load balancer à installer en plus |
| Docker Compose sur le VPS | pas d'orchestrateur : ni HPA, ni NetworkPolicies, ni redémarrage déclaratif (critère P3) |
| Kubernetes managé (cloud) | coût récurrent ; la consigne autorise un orchestrateur auto-hébergé |

## Conséquences

- Coût fixe de ≈ 1 250 Mio (système + K3s), pris en compte dans le budget RAM (`docs/adr/0005-budget-ram-et-hpa.md`).
- Aucune haute disponibilité : la disponibilité démontrée porte sur les pods (replicas, sondes), pas sur le nœud.
  Point à reprendre dans le plan de remédiation.
- ServiceLB masque l'IP des clients (`10.42.0.1`) : `externalTrafficPolicy: Local` à valider avant le rate limiting
  (`docs/cadrage.md` §1.4.14).
