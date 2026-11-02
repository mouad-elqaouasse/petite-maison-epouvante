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

## Résultats (02/11/2026) — ticket clos

- **PR [#20](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/20)** : livrée par Flux (révision
  `ec46dc1`, images `be01e65`) ; Keycloak 26.8.1, realm importé au démarrage (ADR 0014).
- **Écarts corrigés en CI** (Quality Gate SonarCloud) :
  - sécurité D (règle S4502, `csrf.disable()`) : protection CSRF laissée active, déjà ignorée par le resource server
    pour les requêtes avec jeton Bearer ; test ajouté (POST avec jeton → 405, pas 403) ;
  - couverture du nouveau code 77,7 % (`SessionService` à 20 %) : 7 tests unitaires ;
  - duplication 10,8 % (les deux `SecuriteConfig`) : exclues de la détection de copies, duplication assumée pour garder
    deux services indépendants (`sonar-project.properties`, décision du 02/11/2026).
- **Tests d'intégration** (`JetonTest`, Testcontainers Keycloak 26.8.1, realm de test), écrits d'abord (6 échecs sur 7),
  puis verts, 8 par service : `/moi` → 200 avec un jeton valide ; 401 sans jeton, jeton expiré, autre issuer (même
  Keycloak joint par son adresse IP : mêmes clés, autre `iss`), sans audience ; `/version` public ; POST avec jeton non
  bloqué par la protection CSRF. Écart corrigé : avec la sécurité active, `/actuator/health` sur le port de l'API
  renvoyait 401 au lieu de 404 (`VersionTest`) ; seul `/api/**` exige un jeton, test existant inchangé.
- **Front** : `keycloak-angular` 22.0.0, `keycloak-js` 26.2.5 ; page `Accueil` (versions + « Mon compte ») ; 19 tests
  unitaires. Contraste du bouton principal (crème sur `--sang`, 3,63:1) corrigé par un libellé de 19 px en gras
  (« grand texte », seuil 3:1). CSP : `connect-src` et `frame-src` élargis à `auth.…`, `frame-src 'self'` pour le retour
  de la vérification silencieuse ; `silent-check-sso.html` servi avec `frame-ancestors 'self'` et un script externe.
- **Secrets** `keycloak-admin` et `keycloak-demo-users` : présents dans `app` à 16:32:40 UTC (clés identiques à
  `keycloak-secrets.sh`, très probablement une première exécution dont le résultat n'est pas parvenu) ; conservés, valeurs
  jamais affichées.
- **Comptes** (`keycloak-comptes.sh`) : premier lancement arrêté à la connexion, sans aucun changement (`read` renvoie 1
  sans saut de ligne final, `set -e`) ; second défaut trouvé à la relecture (entrée standard du script consommée par
  `kubectl exec -i` sous `bash -s`). Corrigés, puis : `admin-epouvante` créé (seul utilisateur de `master`),
  `admin-demarrage` supprimé, mots de passe des 3 comptes de démonstration appliqués, `frontendUrl` de `master` =
  `http://localhost:8180` (issuer de `master` relu par le tunnel).
- **En ligne** (Playwright, 375 et 1 280 px) : connexion `moderateur-demo` → `MODERATEUR` et `USER` dans les deux
  services ; `membre-demo` → `USER` seulement ; `/moi` → 200 avec jeton ; session conservée au rechargement (check-sso) ;
  déconnexion effective ; aucun blocage CSP ni erreur console ; axe-core 0 violation. Mots de passe passés au seul
  processus `node`, jamais affichés.
- **Exposition** : `auth.…/admin/`, `/admin/master/console/`, `/realms/master/`, `/health/ready`, `/metrics` → 404 ;
  HTTP → 301 vers HTTPS, HSTS `max-age=31536000` ; `/api/*/moi` sans jeton ou avec un faux jeton → 401. Console par
  tunnel : page de connexion du realm `master` sans requête en échec ; port-forward limité à 60 s (`timeout`), port 8180
  libre sur le poste et le VPS.
- **Certificat** `auth-epouvante-tls` réutilisé : aucune nouvelle demande.
- **RAM** : Keycloak prêt en 2 min 26 s (serveur démarré en 24,5 s, import ≈ 6 s) ; **pic au démarrage 733 Mio**
  (limite 768 Mio, exp. 1 : 768 Mio), aucun événement mémoire ; au repos 574 Mio, 623 Mio après les connexions
  (mémoire propre 559 Mio, exp. 1 : 531 Mio). Services 191 à 199 Mio, PostgreSQL 88 Mio. Nœud 2 638 Mio (69 %) ;
  `free -m` : **1 088 Mio disponibles** (1 694 avant le ticket), swap 76 Mio ; 0 redémarrage dans `app`.
- **Non vérifié** : absence de recréation de `admin-demarrage` après un redémarrage de Keycloak (redémarrage non fait,
  décision du 02/11/2026 ; ADR 0014, Conséquences) ; connexion à la console avec `admin-epouvante` (mot de passe lu par
  toi) ; rotation des refresh tokens au-delà de 5 min.
