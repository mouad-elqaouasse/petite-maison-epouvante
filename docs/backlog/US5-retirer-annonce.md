# US5 — Retirer une annonce (modération)

| | |
|---|---|
| Service | `troc-service` |
| Priorité MoSCoW | **Should** (`cadrage.md` §5) |
| Dépendances | **US1a** (annonces publiées et liste). Prérequis : rôle MODERATEUR dans le realm Keycloak. |
| Sources | `cadrage.md` §1.1 point 5, §1.2 point 9, §2, §6 |

## User story

En tant que **modérateur**,
je veux **retirer une annonce publiée**,
afin de **faire disparaître un contenu inapproprié (modération a posteriori)**.

## Critères d'acceptation

```gherkin
# language: fr
Fonctionnalité: Retirer une annonce

  Contexte:
    Étant donné que le membre "alice" (rôle USER) a publié l'annonce "Masque de Jason"
    Et que le membre "modo" a le rôle MODERATEUR

  Scénario: Un modérateur retire une annonce
    Quand modo envoie POST /annonces/{id de "Masque de Jason"}/retrait
    Alors la réponse indique un succès
    Et l'annonce a le statut "RETIREE"

  Scénario: Une annonce retirée n'apparaît plus dans la liste
    Étant donné que modo a retiré l'annonce "Masque de Jason"
    Quand le membre "bob" (rôle USER) consulte GET /annonces
    Alors la liste ne contient pas l'annonce "Masque de Jason"

  Scénario: Un membre sans rôle modérateur ne peut pas retirer une annonce
    Quand le membre "bob" (rôle USER) envoie POST /annonces/{id de "Masque de Jason"}/retrait
    Alors la réponse a le statut 403
    Et l'annonce a toujours le statut "PUBLIEE"

  Scénario: Retirer une annonce sans jeton est refusé
    Étant donné qu'aucun membre n'est connecté
    Quand POST /annonces/{id de "Masque de Jason"}/retrait est appelé sans jeton
    Alors la réponse a le statut 401
    Et l'annonce a toujours le statut "PUBLIEE"
```

## Écran Angular minimal

Sur la liste **« Annonces »** (US1a) : un bouton « Retirer » sur chaque annonce, affiché seulement si le membre connecté a le rôle MODERATEUR,
avec une demande de confirmation, qui appelle `POST /annonces/{id}/retrait`. Masquer le bouton n'est qu'un confort : l'autorisation est vérifiée côté serveur (`@PreAuthorize`).

## Notes

- Le cas « l'auteur lui-même (USER) tente de retirer son annonce » relève de la même règle : 403.
- API décidée le 27/10/2026 : `POST /annonces/{id}/retrait`. Pas de `DELETE`, car l'annonce n'est pas supprimée : elle passe au statut `RETIREE`.
- Hors périmètre : les notifications déjà envoyées pour une annonce retirée ne sont pas modifiées ; pas de signalement ni de modération a priori (`cadrage.md` §6).
