# ADR 0011 — Endpoint `GET /moi` dans chaque service

- Statut : acceptée
- Date : 2026-10-30
- Source : `docs/tickets/README.md` (décision D7), ticket SQ-06 ; ADR 0003

## Contexte

- Les services sont des resource servers qui vérifient signature, `iss`, `aud` et `exp`, et mappent les rôles du realm
  en autorités (`docs/cadrage.md` §1.2.9).
- Le squelette n'a encore aucune fonctionnalité métier : rien ne montrerait qu'un jeton du navigateur est accepté.
- L'expérience 1 a validé l'issuer public et le JWKS interne avec un script, pas avec Spring (rapport exp. 1, Limites).

## Décision

`troc-service` et `notification-service` exposent `GET /api/troc/moi` et `GET /api/notifications/moi`, réservés aux
utilisateurs authentifiés. Réponse : `sub`, nom d'utilisateur et rôles tels que vus par le service (autorités).
`/version` reste public. Le front affiche ces deux réponses après connexion.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Attendre US1a pour valider les jetons | le squelette ne prouverait pas la chaîne d'authentification ; erreurs découvertes en même temps que le métier |
| Protéger `/version` | la vérification post-déploiement du SHA devrait obtenir un jeton ; aucun compte n'est utilisé par la CI |

## Conséquences

- Preuve en ligne, avec `moderateur-demo`, que les rôles arrivent jusqu'aux services ; tests d'intégration sur les cas
  de refus (sans jeton, issuer, audience, expiration).
- Sert de modèle de configuration de sécurité aux user stories.
- Expose au propriétaire du jeton ses propres informations seulement : aucune donnée d'un autre membre.
