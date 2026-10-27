# US1b — Stocker les photos sur Cloudinary

| | |
|---|---|
| Service | `troc-service` |
| Priorité MoSCoW | **Should** (`cadrage.md` §5) — repli : `StockagePhoto` en mémoire, limite documentée |
| Dépendances | **US1a** (interface `StockagePhoto` et publication d'annonce). Expérience 4 (Cloudinary depuis un pod) utile mais non bloquante (Could). |
| Sources | `cadrage.md` §1.1 point 3, §1.4 points 15 et 17, §2, §8 ; `contexte.md` §2 |

## User story

En tant que **membre qui publie une annonce**,
je veux **que ma photo soit stockée de façon durable chez un hébergeur d'images**,
afin que **les autres membres puissent la voir même après un redémarrage ou un changement de pod**.

## Stratégie de test (décision du 27/10/2026)

- **Tous les tests de l'application** (acceptation, intégration, E2E Compose, charge) utilisent une **fausse implémentation** de `StockagePhoto`.
- **L'adaptateur Cloudinary** est testé seul, contre une API Cloudinary simulée par **WireMock** (serveur HTTP de simulation) : succès, erreur, timeout.
- **Aucun appel au vrai Cloudinary en CI.** Le vrai service n'est utilisé que sur le VPS (expérience 4, démonstration).

## Critères d'acceptation

```gherkin
# language: fr
Fonctionnalité: Stocker les photos sur Cloudinary derrière StockagePhoto

  Contexte:
    Étant donné que le membre "alice" (rôle USER) est connecté
    Et que l'implémentation Cloudinary de StockagePhoto est active
    Et que l'API Cloudinary est simulée par WireMock

  Scénario: La photo d'une annonce valide est stockée sur Cloudinary
    Étant donné que l'API Cloudinary simulée répond avec succès, un "public_id" et une URL
    Quand alice publie une annonce valide avec la photo "chucky.jpg" (JPEG, 150 Ko)
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
    Étant donné que l'API Cloudinary simulée répond par une erreur serveur
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse est une erreur 5xx
    Et aucune annonce n'est enregistrée

  Scénario: Un Cloudinary trop lent fait échouer la publication sans bloquer le service
    Étant donné que l'API Cloudinary simulée répond au-delà du délai d'attente configuré
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse est une erreur 5xx renvoyée dès la fin du délai d'attente
    Et aucune annonce n'est enregistrée

  Scénario: La photo est supprimée si l'enregistrement de l'annonce échoue
    Étant donné que l'API Cloudinary simulée répond avec succès
    Et que l'enregistrement en base va échouer
    Quand alice publie une annonce valide avec sa photo
    Alors la réponse est une erreur
    Et l'API Cloudinary simulée reçoit une demande de suppression avec le "photoPublicId"
    Et aucune annonce n'est enregistrée

  Scénario: La clé Cloudinary est lue depuis le secret
    Étant donné que la clé Cloudinary est fournie par une variable d'environnement issue du Secret Kubernetes
    Quand troc-service appelle Cloudinary
    Alors la requête reçue par l'API Cloudinary simulée est authentifiée avec cette clé
    Et aucune clé Cloudinary n'est présente dans le dépôt (contrôle gitleaks)

  Scénario: Le mode jetable est désactivé par défaut
    Étant donné qu'aucune implémentation de StockagePhoto n'est configurée explicitement
    Quand troc-service démarre
    Alors l'implémentation "jetable" n'est pas active
```

## Écran Angular minimal

Pas de nouvel écran. La liste « Annonces » (US1a) affiche la photo à partir de la `photoUrl` Cloudinary.
La CSP (Content Security Policy) du front doit autoriser le domaine des images Cloudinary (`cadrage.md` §1.4 point 14).

## Notes

- Ces scénarios sont des tests d'intégration de l'adaptateur (WireMock), pas des tests d'acceptation boîte noire, qui utilisent la fausse implémentation.
- Le dernier scénario porte sur le mode `StockagePhoto=jetable`, classé **Could** (`cadrage.md` §5) ; il n'est à implémenter que si ce mode est développé.
- Pour le plan de remédiation : photos hébergées hors UE (ADR à rédiger), EXIF non supprimées, dépendance externe (quota, latence, disponibilité) — `cadrage.md` §8.
