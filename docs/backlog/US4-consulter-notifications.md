# US4 — Consulter mes notifications et les marquer comme lues

| | |
|---|---|
| Service | `notification-service` |
| Priorité MoSCoW | **Should** (`cadrage.md` §5) |
| Dépendances | **US3** (création des notifications et lecture minimale). |
| Sources | `cadrage.md` §1.1 point 4, §1.2 point 9, §2 |

## User story

En tant que **membre authentifié**,
je veux **consulter mes notifications et marquer comme lues celles que j'ai vues**,
afin de **repérer facilement les nouvelles annonces que je n'ai pas encore regardées**.

## Critères d'acceptation

```gherkin
# language: fr
Fonctionnalité: Consulter mes notifications et les marquer comme lues

  Contexte:
    Étant donné les membres "alice" et "bob" (rôle USER)
    Et qu'alice a une notification "ALERTE" non lue
    Et que bob a une notification "ALERTE" non lue

  Scénario: Je vois mes notifications avec leur état de lecture
    Quand alice consulte GET /notifications
    Alors la réponse a le statut 200
    Et la liste contient sa notification, marquée non lue

  Scénario: Je ne vois que mes propres notifications
    Quand alice consulte GET /notifications
    Alors la liste ne contient aucune notification de bob

  Scénario: Marquer une de mes notifications comme lue
    Quand alice envoie PATCH /notifications/{id de sa notification} avec {"lue": true}
    Alors la réponse indique un succès
    Et cette notification apparaît comme lue dans GET /notifications

  Scénario: Marquer la notification d'un autre membre renvoie 404
    Quand alice envoie PATCH /notifications/{id de la notification de bob} avec {"lue": true}
    Alors la réponse a le statut 404
    Et la notification de bob reste non lue

  Scénario: Consulter les notifications sans jeton est refusé
    Étant donné qu'aucun membre n'est connecté
    Quand GET /notifications est appelé sans jeton
    Alors la réponse a le statut 401
```

## Écran Angular minimal

Évolution de la page **« Mes notifications »** (US3) :
- notifications non lues mises en évidence (pas seulement par la couleur, RGAA) ;
- bouton « Marquer comme lue » sur chaque notification non lue (`PATCH /notifications/{id}`) ;
- le compteur de l'en-tête affiche le nombre de notifications **non lues**.

## Notes

- 404 plutôt que 403 sur la notification d'un autre membre : on ne révèle pas son existence.
- API décidée le 27/10/2026 : `PATCH /notifications/{id}` avec le corps `{"lue": true}`.
