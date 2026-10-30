# SQ-06 — Keycloak, realm et validation des jetons

| | |
|---|---|
| Dépend de | SQ-03, SQ-04 ; ADR 0011 (D7) ; comptes de démonstration (README) |
| Sources | `cadrage.md` §1.2.9, §1.4.14 ; ADR 0003 ; rapport exp. 1 §7-9 et « À reprendre au déploiement de `app` » (journal) |

## But

Un visiteur se connecte depuis la page (PKCE) et chaque service valide son jeton : issuer public, clés lues en interne.

## Contenu

- **Keycloak** : image officielle standard (version de l'exp. 1, épinglée), commande `start` ; l'image optimisée est
  le bonus SQ-12. `deploy/app/keycloak/` : 1 pod 512/768 Mio, base `keycloak` (SQ-04), `KC_HOSTNAME=https://auth.…`,
  en-têtes de proxy, santé et métriques actives ; `startupProbe` assez longue pour le démarrage non optimisé ;
  Service interne.
- **Ingress `auth.…`** : seuls `/realms/epouvante/**` et `/resources/**` routés ; `/admin` et `/realms/master` → 404 ;
  `secretName: auth-epouvante-tls` ; redirection HTTPS et HSTS par les middlewares (pas `sslRequired`, ADR 0003 §4).
- **Realm `epouvante`** (`keycloak/realm-epouvante.json`, sans aucun mot de passe) : rôles `USER`, `MODERATEUR` ;
  client public `epouvante-front` (PKCE S256, redirections limitées à `https://epouvante.…`) ; mappers d'audience
  `troc-service` et `notification-service` ; access token 5 min ; rotation des refresh tokens ; comptes de démonstration
  `membre-demo`, `abonne-demo` (rôle `USER`) et `moderateur-demo` (`USER`, `MODERATEUR`), **sans mot de passe** dans le
  fichier. Importé au démarrage s'il est absent.
- **Administration** : administrateur permanent créé par tunnel, administrateur de démarrage supprimé ; URL de frontend
  du realm `master` sur l'adresse du tunnel.
- **Mots de passe** : tous générés sur le VPS (`openssl rand`) dans des Secrets (`keycloak-admin`, `keycloak-demo-users`),
  jamais affichés ; appliqués aux comptes par un script adapté de `exp1/72` (lecture sur l'entrée standard). Tu les lis toi-même.
- **Services** : resource servers ; `issuer-uri` public, `jwk-set-uri` interne **fixé explicitement** ; contrôle de
  l'audience ; rôles du realm → autorités ; `GET /api/<service>/moi` authentifié (D7) ; `/version` reste public.
- **Front** : `keycloak-angular`, Authorization Code + PKCE, jeton en mémoire ; bouton Se connecter / Se déconnecter ;
  après connexion, affiche le nom et les rôles renvoyés par les deux `/moi`. Jeton ajouté seulement aux appels `/api/`.
  CSP élargie à `https://auth.…` pour `connect-src` et `frame-src` si nécessaire.

## Critères d'acceptation

- Tests d'intégration (écrits d'abord, Testcontainers Keycloak avec un realm de test) : `/moi` → 200 avec un jeton
  valide ; 401 sans jeton, avec un jeton expiré, d'un autre issuer ou sans la bonne audience.
- En ligne : connexion avec `moderateur-demo` → la page affiche `MODERATEUR` ; avec `membre-demo`, seulement `USER` ;
  déconnexion effective. Aucun de ces comptes n'est utilisé par la CI.
- `https://auth.…/admin/` et `/realms/master/` → 404 ; console accessible par tunnel seulement.
- Démarrage de Keycloak sans dépasser la limite (pic mesuré, comparé aux 768 Mio de l'exp. 1) ; certificat réutilisé.
