# US2 — Gérer mes catégories d'intérêt

| | |
|---|---|
| Service | `notification-service` (l'écran utilise aussi `GET /categories` de `troc-service`) |
| Priorité MoSCoW | **Must** (`cadrage.md` §5) |
| Dépendances | **US1a** pour l'écran (`GET /categories`). L'API des abonnements n'en dépend pas. Ordre prévu : après US1a (`cadrage.md` §2). |
| Sources | `cadrage.md` §1.1 point 1, §1.2 points 7 et 9, §2 ; `contexte.md` §1 |
| Décisions | D1 à D4 validées le 05/11/2026 : ADR 0024 (liste des catégories copiée) ; CSRF : ADR 0023 point 2 |

## User story

En tant que **membre authentifié**,
je veux **choisir les catégories d'objets qui m'intéressent**,
afin d'**être prévenu quand une annonce de ces catégories est publiée**.

## Critères d'acceptation

```gherkin
# language: fr
Fonctionnalité: Gérer mes catégories d'intérêt

  Contexte:
    Étant donné que le membre "alice" (rôle USER) est connecté

  Scénario: Un nouveau membre n'a aucune catégorie d'intérêt
    Quand alice consulte GET /abonnements
    Alors la réponse a le statut 200
    Et la liste est vide

  Scénario: Ajouter une catégorie d'intérêt
    Quand alice envoie PUT /abonnements/BD
    Alors la réponse indique un succès
    Et GET /abonnements renvoie "BD"

  Scénario: S'abonner à plusieurs catégories
    Étant donné qu'alice est abonnée à "BD"
    Quand alice envoie PUT /abonnements/Films
    Alors GET /abonnements renvoie "BD" et "Films"

  Scénario: Retirer une catégorie d'intérêt
    Étant donné qu'alice est abonnée à "BD" et "Films"
    Quand alice envoie DELETE /abonnements/BD
    Alors la réponse indique un succès
    Et GET /abonnements renvoie "Films"

  Scénario: Une catégorie inconnue est refusée
    Quand alice envoie PUT /abonnements/Voitures
    Alors la réponse a le statut 400
    Et GET /abonnements reste inchangé

  Scénario: Les abonnements sont propres à chaque membre
    Étant donné qu'alice est abonnée à "BD"
    Quand le membre "bob" (rôle USER) consulte GET /abonnements
    Alors la liste de bob ne contient pas "BD"

  Scénario: Gérer ses catégories sans jeton est refusé
    Étant donné qu'aucun membre n'est connecté
    Quand GET /abonnements est appelé sans jeton
    Alors la réponse a le statut 401
```

## Écran Angular minimal

Page **« Mes centres d'intérêt »** (route protégée) :
- une case à cocher par catégorie renvoyée par `GET /categories` (jamais codée en dur), cochée si elle figure dans `GET /abonnements` ;
- cocher = `PUT /abonnements/{categorie}`, décocher = `DELETE /abonnements/{categorie}` ;
- message de confirmation ou d'erreur accessible (zone `aria-live`).

## Notes

- API décidée le 27/10/2026 : `GET /abonnements`, `PUT` et `DELETE /abonnements/{categorie}`.
  `PUT` rend l'ajout idempotent (rejouer la requête ne crée pas de doublon).
- Le membre est identifié par le `sub` de son jeton, jamais par un paramètre de la requête (`cadrage.md` §1.1 point 2, même principe).
- `notification-service` détient les abonnements ; `troc-service` ne les connaît pas (`cadrage.md` §1.2 point 7).
- La liste des catégories existe donc dans les deux services (`GET /categories` côté `troc-service`, validation des abonnements côté
  `notification-service`) : elles doivent rester identiques. Copie de l'enum, figée par `CategorieTest` (ADR 0024, D1).

## Précisions validées le 05/11/2026 (ADR 0024)

- Chemins réels : `/api/notifications/abonnements[/{categorie}]` (ADR 0006) ; catégorie passée par son **libellé**,
  encodé dans l'URL (« Jeux de société » → `Jeux%20de%20soci%C3%A9t%C3%A9`) (D2).
- « La réponse indique un succès » = **204**. `PUT` et `DELETE` sont idempotents : retirer une catégorie non suivie
  répond aussi 204 ; une catégorie inconnue → 400 (ProblemDetail), aussi pour `DELETE` (D3).
- `PUT` ou `DELETE` sans jeton → 401 (et non 403) : protection CSRF ignorée sur `/api/**`, sans cookie (ADR 0023).
- Tests : `GererAbonnementsAcceptationTest` (un test par scénario, même nom, plus les règles ci-dessus) ;
  écran : `centres-interet.spec.ts`. Captures : `docs/soutenance/captures/us2-ui/`.
