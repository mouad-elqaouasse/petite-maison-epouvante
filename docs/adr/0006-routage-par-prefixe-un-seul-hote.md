# ADR 0006 — Routage par préfixe sur un seul hôte public

- Statut : acceptée
- Date : 2026-10-30
- Source : `docs/tickets/README.md` (décision D1), ticket SQ-01

## Contexte

- Le front Angular appelle `troc-service` et `notification-service` depuis le navigateur (`docs/cadrage.md` §1.2.9).
- Deux noms publics ont déjà un certificat Let's Encrypt : `epouvante.menuly-app.com` et `auth.epouvante.menuly-app.com`
  (ADR 0001) ; chaque nouveau nom coûte une émission et passe d'abord par `letsencrypt-staging`.
- Traefik est le seul point d'entrée (`docs/cadrage.md` §1.4.14).

## Décision

Sur `epouvante.menuly-app.com`, un Ingress par composant, routé par préfixe de chemin :

| Préfixe | Composant |
|---|---|
| `/api/troc/` | `troc-service` |
| `/api/notifications/` | `notification-service` |
| `/` | front (nginx) |

Les services exposent leurs endpoints sous leur préfixe complet (pas de réécriture de chemin par Traefik).
Keycloak garde son propre nom, `auth.epouvante.menuly-app.com` (ADR 0003).

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Un sous-domaine par service (`troc.…`, `notifications.…`) | deux certificats de plus ; appels entre origines différentes, donc CORS à configurer et à sécuriser |
| Préfixe retiré par un middleware Traefik (`StripPrefix`) | chemins différents entre le cluster et le poste de dev ; tests d'acceptation moins proches de la réalité |
| Passerelle d'API dédiée | composant et RAM en plus, sans besoin dans le POC (budget de l'ADR 0005) |

## Conséquences

- Le front et les API ont la même origine : pas de CORS, CSP limitée à `'self'` pour `connect-src` (plus Keycloak).
- Un seul Secret TLS (`epouvante-tls`) pour le front et les API.
- Le rate limiting de `POST /annonces` (`docs/cadrage.md` §1.4.14) s'appliquera à l'Ingress de `troc-service` seul.
- Ajouter un service = ajouter un préfixe ; collision de préfixes à éviter à la revue.
