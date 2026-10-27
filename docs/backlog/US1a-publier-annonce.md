# US1a — Publier une annonce de troc avec sa photo

| | |
|---|---|
| Service | `troc-service` |
| Priorité MoSCoW | **Must** (`cadrage.md` §5) |
| Dépendances | Aucune story. Prérequis technique : realm Keycloak de test (comptes USER et MODERATEUR). |
| Sources | `cadrage.md` §1.1 (points 1, 2, 3, 5, 6), §1.2 (points 7, 8), §2 ; `consignes.md` §1.2.1 |

## User story

En tant que **membre authentifié**,
je veux **publier une annonce de troc (échange ou don) accompagnée d'une photo**,
afin que **les autres membres puissent la voir et me proposer un troc**.

## Critères d'acceptation

Les tests d'acceptation passent par l'API (boîte noire) avec `StockagePhoto` simulé en mémoire.
Chaque scénario correspond à un test JUnit qui porte le même nom (`cadrage.md` §1.3 point 12).

```gherkin
# language: fr
Fonctionnalité: Publier une annonce de troc avec sa photo

  Contexte:
    Étant donné que le membre "alice" (rôle USER) est connecté
    Et que le stockage des photos est simulé en mémoire

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
- Une photo invalide est refusée **avant** tout appel à `StockagePhoto`.
- Fiabilité de l'émission (après commit, publisher confirms, message durable) : `cadrage.md` §1.2 point 8. La perte possible entre commit et publication est une limite assumée (outbox dans le plan de remédiation).
- Hors de cette story : Cloudinary et suppression de la photo si l'enregistrement échoue (US1b), retrait par un modérateur (US5).
