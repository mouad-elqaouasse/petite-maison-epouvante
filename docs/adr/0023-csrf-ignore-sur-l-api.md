# ADR 0023 — Protection CSRF ignorée sur `/api/**` (API sans session, jetons Bearer seulement)

- Statut : acceptée
- Date : 2026-11-05
- Source : US1a, scénario « Publier sans jeton est refusé » ; `docs/cadrage.md` §1.2.9 ; ADR 0003, ADR 0011
- Remplace : le choix de SQ-06 « protection CSRF laissée active » (commentaire de `SecuriteConfig`)

## Contexte

- Une attaque CSRF (falsification de requête intersite) fait envoyer par le navigateur d'un membre une requête vers
  notre API, avec les **identifiants que le navigateur joint tout seul** : cookie de session, authentification HTTP.
- Nos services sont des resource servers : `SessionCreationPolicy.STATELESS` (aucune session, aucun cookie
  `JSESSIONID`) ; l'authentification passe **uniquement** par l'en-tête `Authorization: Bearer`, ajouté par le code du
  front (intercepteur de keycloak-angular, jeton en mémoire, jamais en cookie ni en `localStorage`, `cadrage.md` §1.2.9).
  Un site tiers ne peut ni lire ce jeton ni le faire ajouter par le navigateur.
- SQ-06 avait laissé la protection active : Spring Security l'ignore pour les requêtes porteuses d'un jeton Bearer.
  Mais pour une requête **sans** jeton, le filtre CSRF passe avant l'authentification : `POST /api/troc/annonces`
  sans jeton recevait **403** (jeton CSRF absent) au lieu du **401** exigé par US1a.
- Un test vérifie qu'aucune réponse de l'API ne pose de cookie (`l_api_ne_cree_ni_session_ni_cookie`).

## Décision

1. `troc-service` ignore la protection CSRF sur `/api/**` :
   `csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))`, avec `SessionCreationPolicy.STATELESS` inchangé.
2. Même réglage pour `notification-service` quand il exposera ses premières routes d'écriture (US2 :
   `PUT` et `DELETE /abonnements/{categorie}`), avec le même test.
3. Condition de validité : **aucun cookie d'authentification** sur l'API. Si un jour l'API acceptait une session ou un
   cookie (par exemple un BFF, `cadrage.md` §8), la protection CSRF devrait être réactivée.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Garder la protection active | 401 impossible sans jeton (403 à la place) ; ne protège rien sans cookie |
| Modifier l'ordre des filtres pour authentifier avant le filtre CSRF | configuration fragile et non standard pour un gain nul |
| Accepter 403 au lieu de 401 | contraire au critère d'acceptation d'US1a ; un client ne saurait pas qu'il doit se connecter |

## Conséquences

- Un `POST`, `PUT` ou `DELETE` sans jeton reçoit 401 ; avec un jeton, le comportement est inchangé
  (`post_avec_un_jeton_n_est_pas_bloque_par_la_protection_csrf`, SQ-06).
- SonarCloud signale ce réglage comme point sensible (règle `java:S4502`) : il est revu et marqué *Safe* avec la
  justification ci-dessous.

## Justification pour SonarCloud (point sensible `java:S4502`, statut *Safe*)

> Safe : API REST sans état (SessionCreationPolicy.STATELESS), authentifiée uniquement par jeton Bearer dans l'en-tête
> Authorization, ajouté par le code du front et jamais stocké en cookie. Aucun cookie de session ni d'authentification
> n'est posé (test l_api_ne_cree_ni_session_ni_cookie), donc un site tiers ne peut pas faire envoyer d'identifiants par
> le navigateur : la protection CSRF est sans objet. Restreinte à /api/**. Voir docs/adr/0023-csrf-ignore-sur-l-api.md.
