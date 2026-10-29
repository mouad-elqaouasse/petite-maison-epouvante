# ADR 0003 — Keycloak : issuer public, JWKS interne, console d'administration non publique

- Statut : acceptée
- Date : 2026-10-29
- Source des mesures : `docs/experimentations/exp1-socle-k3s-rapport.md` (partie 4) ; détail dans le journal, partie 4

## Contexte

- Le sujet demande une authentification/autorisation « via un serveur comme Keycloak » (`docs/sujet/consignes.md` §1.2.3).
- Le cadrage prévoit `issuer-uri` public et `jwk-set-uri` interne (`docs/cadrage.md` §1.2.9) et une console
  d'administration non publique (§1.4.14).
- Expérience 1 : un jeton émis par le navigateur porte l'issuer public ; validé dans un pod avec les clés lues par
  l'adresse interne, il est accepté ; il est refusé avec l'issuer interne, une audience inconnue ou un rôle ajouté.
  La console échoue tant que l'URL de frontend du realm `master` reste publique et non routée.

## Décision

1. Keycloak 26 en mode production (`start`), `KC_HOSTNAME` = URL publique `https://auth.<domaine>`.
2. Resource servers Spring : `issuer-uri` = URL publique ; `jwk-set-uri` = URL interne du Service, **fixée explicitement**
   (pas de découverte, qui renverrait l'URL publique).
3. Seul le realm applicatif est routé par Traefik ; `/admin` et `/realms/master` ne le sont pas (404).
   Console d'administration par tunnel (`ssh -t` + `kubectl port-forward`), URL de frontend du realm `master` réglée sur
   l'adresse du tunnel.
4. HTTPS forcé par Traefik (redirection et HSTS), pas par `sslRequired` de Keycloak (`docs/cadrage.md` §1.4.14).

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Issuer interne partout | jeton du navigateur refusé par les services (issuer différent) |
| Clés lues par l'URL publique | aller-retour par Internet et dépendance au DNS et au certificat pour chaque service |
| Console publique protégée par mot de passe | surface d'attaque inutile pour un POC |

## Conséquences

- Les services doivent réessayer la lecture du JWKS au démarrage (refus total d'environ 1 s, `docs/adr/0004-networkpolicies-refus-par-defaut.md`).
- À faire pour `app` : administrateur permanent à la place de l'administrateur de démarrage ; image optimisée
  (`start --optimized`) pour réduire le pic de mémoire au démarrage.
- Keycloak reste le plus gros poste de RAM (≈ 600 Mio, `docs/adr/0005-budget-ram-et-hpa.md`).
