# US1a — Publier une annonce de troc avec sa photo

| | |
|---|---|
| Service | `troc-service` |
| Priorité MoSCoW | **Must** (`cadrage.md` §5) |
| Dépendances | Aucune story. Prérequis techniques : realm Keycloak de test (comptes USER et MODERATEUR) ; Secret `cloudinary` créé sur le VPS (`deploy/scripts/cloudinary-secrets.sh`). |
| Sources | `cadrage.md` §1.1 (points 1, 2, 3, 5, 6), §1.2 (points 7, 8), §2 ; `consignes.md` §1.2.1 ; ADR 0019 à 0022 |
| Périmètre | **Élargi le 05/11/2026** : la photo est stockée sur Cloudinary dès cette story (ancienne US1b, fusionnée ici). |

## User story

En tant que **membre authentifié**,
je veux **publier une annonce de troc (échange ou don) accompagnée d'une photo**,
afin que **les autres membres puissent la voir et me proposer un troc**.

## Critères d'acceptation

Les tests d'acceptation passent par l'API (boîte noire) avec la fausse implémentation de `StockagePhoto` (profil Spring
`photo-factice`, ADR 0021). Chaque scénario correspond à un test JUnit qui porte le même nom (`@DisplayName`,
`cadrage.md` §1.3 point 12). Chemins réels : `/api/troc/annonces` et `/api/troc/categories` (ADR 0006).
Réponse 400 : ProblemDetail avec un message par champ (`erreurs.titre`, `erreurs.photo`…), affiché sous le champ.

```gherkin
# language: fr
Fonctionnalité: Publier une annonce de troc avec sa photo

  Contexte:
    Étant donné que le membre "alice" (rôle USER) est connecté
    Et que le stockage des photos est simulé (profil photo-factice)

  Scénario: Publier une annonce valide avec sa photo
    Quand alice envoie POST /annonces avec le titre "Figurine Chucky 1988", la description "Bon état",
      la catégorie "Figurines", le type "ECHANGE" et la photo "chucky.jpg" (JPEG, 150 Ko)
    Alors la réponse a le statut 201
    Et l'annonce renvoyée a le statut "PUBLIEE"
    Et l'annonce renvoyée a une date de création "creeLe"
    Et l'annonce renvoyée a une URL de photo "photoUrl"

  Plan du scénario: Le titre doit faire entre 3 et 80 caractères
    Quand alice publie une annonce valide dont le titre fait <longueur> caractères
    Alors la réponse a le statut <statut>

    Exemples:
      | longueur | statut |
      | 2        | 400    |
      | 3        | 201    |
      | 80       | 201    |
      | 81       | 400    |

  Scénario: Une description de plus de 1000 caractères est refusée
    Quand alice publie une annonce valide dont la description fait 1001 caractères
    Alors la réponse a le statut 400

  Scénario: Une catégorie hors de la liste fermée est refusée
    Quand alice publie une annonce valide avec la catégorie "Voitures"
    Alors la réponse a le statut 400

  Scénario: Un type autre que ECHANGE ou DON est refusé
    Quand alice publie une annonce valide avec le type "VENTE"
    Alors la réponse a le statut 400

  Scénario: Une annonce sans photo est refusée
    Quand alice publie une annonce valide sans photo
    Alors la réponse a le statut 400
    Et aucune annonce n'est enregistrée

  Scénario: Une annonce avec plus d'une photo est refusée
    Quand alice publie une annonce valide avec deux photos JPEG
    Alors la réponse a le statut 400
    Et aucune photo n'est stockée

  Scénario: Une photo de plus de 2 Mo est refusée
    Quand alice publie une annonce valide avec une photo JPEG de 2 Mo plus 1 octet
    Alors la réponse a le statut 400
    Et aucune photo n'est stockée

  Scénario: Une photo dans un format non autorisé est refusée
    Quand alice publie une annonce valide avec la photo "chauve-souris.gif" (GIF)
    Alors la réponse a le statut 400
    Et aucune photo n'est stockée

  Scénario: Un fichier déguisé en image est refusé
    Étant donné un fichier texte renommé "photo.jpg" et déclaré "image/jpeg"
    Quand alice publie une annonce valide avec ce fichier
    Alors la réponse a le statut 400
    Et aucune photo n'est stockée

  Scénario: Publier sans jeton est refusé
    Étant donné qu'aucun membre n'est connecté
    Quand une annonce valide est envoyée sur POST /annonces sans jeton
    Alors la réponse a le statut 401
    Et aucune annonce n'est enregistrée

  Scénario: L'auteur est tiré du jeton et non du corps de la requête
    Quand alice publie une annonce valide en ajoutant dans le corps "auteurId" égal à l'identifiant de "bob"
    Alors la réponse a le statut 201
    Et l'auteur de l'annonce est l'identifiant ("sub") du jeton d'alice

  Scénario: L'événement AnnoncePubliee est émis après l'enregistrement
    Quand alice publie une annonce valide dans la catégorie "Films"
    Alors l'annonce est enregistrée en base
    Et un événement "AnnoncePubliee" est publié avec l'id de l'annonce, la catégorie "Films",
      l'auteur alice, le titre, la date de création et l'URL de la photo

  Scénario: L'annonce publiée apparaît dans la liste
    Étant donné qu'alice a publié l'annonce "Affiche Halloween 1978"
    Quand le membre "bob" (rôle USER) consulte GET /annonces
    Alors la réponse a le statut 200
    Et la liste contient l'annonce "Affiche Halloween 1978" avec sa photo

  Scénario: Consulter la liste sans jeton est refusé
    Étant donné qu'aucun membre n'est connecté
    Quand GET /annonces est appelé sans jeton
    Alors la réponse a le statut 401

  Scénario: La liste des catégories est fournie par l'API
    Quand alice consulte GET /categories
    Alors la réponse a le statut 200
    Et la liste contient exactement "Figurines", "Films", "BD", "Jeux de société" et "Goodies"
```

### Stockage sur Cloudinary (repris de l'ancienne US1b le 05/11/2026)

Tests de l'adaptateur et de la publication avec l'implémentation Cloudinary active, face à une API Cloudinary simulée
par **WireMock** ; **aucun appel au vrai Cloudinary en CI** (`cadrage.md` §1.3 point 12). Réponses simulées relevées à
l'expérience 4 (`docs/experimentations/exp4-cloudinary-rapport.md` §5).

```gherkin
# language: fr
Fonctionnalité: Stocker la photo sur Cloudinary derrière StockagePhoto

  Contexte:
    Étant donné que le membre "alice" (rôle USER) est connecté
    Et que l'implémentation Cloudinary de StockagePhoto est active (aucun profil)
    Et que l'API Cloudinary est simulée par WireMock

  Scénario: La photo d'une annonce valide est stockée sur Cloudinary
    Étant donné que l'API Cloudinary simulée répond avec succès, un "public_id" et une URL
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse a le statut 201
    Et l'annonce enregistrée contient le "photoPublicId" et la "photoUrl" renvoyés par Cloudinary

  Scénario: La photo est envoyée telle quelle
    Quand alice publie une annonce valide avec la photo "chucky.jpg"
    Alors les octets reçus par l'API Cloudinary simulée sont identiques à ceux du fichier envoyé par alice

  Scénario: Une photo invalide n'est jamais envoyée à Cloudinary
    Quand alice publie une annonce avec une photo GIF
    Alors la réponse a le statut 400
    Et l'API Cloudinary simulée ne reçoit aucun appel

  Scénario: Une erreur de Cloudinary fait échouer la publication
    Étant donné que l'API Cloudinary simulée répond par une erreur (401 « Invalid api_key <clé> »)
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse est une erreur 5xx (502)
    Et aucune annonce n'est enregistrée
    Et ni la réponse ni les logs ne contiennent le message de Cloudinary ou la clé

  Scénario: Un Cloudinary trop lent fait échouer la publication sans bloquer le service
    Étant donné que l'API Cloudinary simulée répond au-delà du délai d'attente configuré
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse est une erreur 5xx renvoyée dès la fin du délai d'attente
    Et aucune annonce n'est enregistrée

  Scénario: La photo est supprimée si l'enregistrement de l'annonce échoue
    Étant donné que la photo a été stockée
    Et que l'enregistrement en base va échouer
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse est une erreur
    Et la photo est supprimée (destroy avec invalidate=true et le "photoPublicId")

  Scénario: La clé Cloudinary est lue depuis le secret
    Étant donné que la clé Cloudinary est fournie par une variable d'environnement issue du Secret Kubernetes
    Quand troc-service appelle Cloudinary
    Alors la requête reçue par l'API Cloudinary simulée est signée avec cette clé
    Et aucune clé Cloudinary n'est présente dans le dépôt (contrôle gitleaks)

  Scénario: Le mode factice est désactivé par défaut
    Étant donné qu'aucun profil Spring n'est actif
    Quand troc-service démarre
    Alors l'implémentation "photo-factice" n'est pas active
```

Tests correspondants : `CloudinaryAcceptationTest` (publication de bout en bout), `StockagePhotoCloudinaryTest`
(adaptateur seul, dont les 401 et 400 observés à l'expérience 4), `PublicationAnnonceTest` (suppression de la photo).

## Écran Angular minimal

Connexion par Keycloak (Authorization Code + PKCE, `keycloak-angular`). Les deux routes sont protégées par un guard.

1. **« Publier une annonce »** — formulaire :
   - titre (texte, 3 à 80 caractères), description (zone de texte, 1000 caractères max) ;
   - catégorie (liste déroulante remplie par `GET /categories`, jamais codée en dur dans le front) ;
   - type (boutons radio : Échange / Don) ;
   - photo (champ fichier, `accept="image/jpeg,image/png,image/webp"`, 2 Mo max) ;
   - bouton « Publier » ; erreurs affichées sous chaque champ (contrôles côté client pour le confort, la règle fait foi côté serveur) ;
   - en cas de succès, redirection vers la liste.
2. **« Annonces »** — liste des annonces publiées : photo, titre, catégorie, type, date.

Accessibilité : chaque champ a un libellé, les messages d'erreur sont reliés au champ (vérifié par axe-core en E2E).

## Notes

- `GET /categories` (décision du 27/10/2026) : `troc-service` expose la liste fermée des catégories, utilisée par les écrans de US1a et US2.
  Comme le reste de l'API, il exige un jeton (seuls news et articles sont publics, `contexte.md` §2).
- Une photo invalide est refusée **avant** tout appel à `StockagePhoto` : Cloudinary accepte 10 Mio et le BMP, le contrôle
  de `troc-service` est la seule barrière (exp. 4).
- `GET /annonces` renvoie les 50 annonces publiées les plus récentes (liste bornée pour les campagnes de charge, 05/11/2026).
- Fiabilité de l'émission (après commit, publisher confirms, message durable) : `cadrage.md` §1.2 point 8 ; contrat de
  l'événement : `docs/schemas/evenement-annonce-publiee.md` (ADR 0020). La perte possible entre commit et publication est
  une limite assumée (outbox dans le plan de remédiation) ; chaque échec est compté (`annonces_publiees_emission_echecs_total`).
- Sortie réseau `troc-service` → Internet en 443 (NetworkPolicy `troc-sortie-https`, ADR 0021) ; CSP du front :
  `img-src 'self' https://res.cloudinary.com`.
- Guard des deux écrans : attend la vérification silencieuse de session 10 s au plus, puis redirige vers la page de
  connexion de Keycloak (pas de page blanche si l'iframe est refusée) ; `checkLoginIframe` reste désactivé.
- `POST` sans jeton → 401 et non 403 : protection CSRF ignorée sur `/api/**`, API sans session ni cookie (ADR 0023).
- Hors de cette story : retrait par un modérateur (US5), réencodage des photos et suppression des métadonnées EXIF
  (`cadrage.md` §6 et §8).
