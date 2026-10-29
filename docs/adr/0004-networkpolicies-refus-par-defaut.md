# ADR 0004 — NetworkPolicies : refus par défaut et ouvertures explicites

- Statut : acceptée
- Date : 2026-10-29
- Source des mesures : `docs/experimentations/exp1-socle-k3s-rapport.md` (partie 5B) ; détail dans le journal, partie 5, étapes B1 à B9

## Contexte

- Le cadrage prévoit des NetworkPolicies dans `app` : refus par défaut, puis ouvertures une par une (`docs/cadrage.md` §1.4.14).
- Expérience 1 : K3s (kube-router) applique les règles. Avec 15 règles, la matrice de 19 flux est conforme : un pod non
  autorisé ne joint ni PostgreSQL ni Keycloak, les flux légitimes passent.
- Trois pièges mesurés : les connexions déjà ouvertes ne sont pas coupées par une nouvelle règle ; la sortie vers l'API
  Kubernetes doit viser l'IP du nœud sur le port 6443 (l'adresse du Service ne suffit pas) ; un pod neuf subit environ 1 s
  de refus total au démarrage.

## Décision

1. Dans `app` : une règle de refus total (entrée et sortie), puis une règle par flux nécessaire (DNS, Traefik → front et
   API, services → PostgreSQL, RabbitMQ et Keycloak, Prometheus → cibles, `troc-service` → Cloudinary, Flux → GitHub et GHCR).
2. Règle de sortie vers l'API Kubernetes en deux parties : adresse du Service (443) **et** IP du nœud (6443).
3. Validation de chaque règle par une matrice de flux (attendu comparé à l'obtenu), lancée d'abord **sans règle** (référence),
   puis sur des connexions neuves et après redémarrage des pods concernés.
4. `troc-service` et `notification-service` réessaient leurs premières connexions (PostgreSQL, RabbitMQ, JWKS) au lieu d'échouer.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Pas de NetworkPolicies (cloisonnement par namespace seul) | tout pod du cluster joint PostgreSQL (constaté sans règle) |
| Ouverture large par namespace | n'empêche pas un pod compromis de `app` de joindre la base |
| Service mesh (mTLS) | hors périmètre V1 et trop lourd pour 4 Go (`docs/cadrage.md` §6) |

## Conséquences

- Les NetworkPolicies sont ajoutées **en dernier**, une fois l'application fonctionnelle (`docs/cadrage.md` §1.4.14).
- Une règle trop stricte peut rester invisible tant que les pods ne redémarrent pas : le redémarrage fait partie de la validation.
- Coût en RAM non mesurable à cette échelle.
- Outils réutilisables : `experimentations/exp1/90` à `98`.
