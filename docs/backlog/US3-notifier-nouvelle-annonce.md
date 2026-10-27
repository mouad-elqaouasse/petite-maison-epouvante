# US3 — Être notifié d'une nouvelle annonce

| | |
|---|---|
| Service | `notification-service` (consommateur de `AnnoncePubliee`) |
| Priorité MoSCoW | **Must** (`cadrage.md` §5) |
| Dépendances | **US1a** (émission de `AnnoncePubliee`) et **US2** (abonnements). |
| Sources | `cadrage.md` §1.1 point 4, §1.2 points 7, 8, 10, §2 |

## User story

En tant que **membre**,
je veux **être notifié quand une annonce est publiée dans une catégorie qui m'intéresse, et quand ma propre annonce est publiée**,
afin de **ne pas manquer une occasion de troc et de savoir que ma publication a abouti**.

## Critères d'acceptation

Tests d'acceptation asynchrones : attente bornée (Awaitility) de l'apparition des notifications.

```gherkin
# language: fr
Fonctionnalité: Notifier les membres d'une nouvelle annonce

  Contexte:
    Étant donné les membres "alice", "bob" et "carole" (rôle USER)
    Et que bob est abonné à la catégorie "Figurines"
    Et que carole est abonnée à la catégorie "BD" uniquement

  Scénario: L'auteur reçoit une confirmation
    Quand alice publie une annonce dans la catégorie "Figurines"
    Alors alice reçoit une notification "CONFIRMATION" "Votre annonce est publiée" pour cette annonce

  Scénario: Un membre abonné à la catégorie reçoit une alerte
    Quand alice publie une annonce dans la catégorie "Figurines"
    Alors bob reçoit une notification "ALERTE" "Nouvelle annonce" pour cette annonce

  Scénario: Un membre non abonné à la catégorie ne reçoit rien
    Quand alice publie une annonce dans la catégorie "Figurines"
    Alors carole ne reçoit aucune notification pour cette annonce

  Scénario: Un auteur abonné à la catégorie reçoit seulement la confirmation
    Étant donné qu'alice est abonnée à la catégorie "Figurines"
    Quand alice publie une annonce dans la catégorie "Figurines"
    Alors alice reçoit exactement une notification pour cette annonce, de type "CONFIRMATION"

  Scénario: Un message reçu en double ne crée qu'une seule notification
    Étant donné qu'un événement "AnnoncePubliee" a déjà été traité pour une annonce de la catégorie "Figurines"
    Quand le même événement est livré une seconde fois
    Alors bob a toujours exactement une notification "ALERTE" pour cette annonce
    Et alice a toujours exactement une notification "CONFIRMATION" pour cette annonce

  Scénario: Un message impossible à traiter est retenté 3 fois puis placé en DLQ
    Étant donné que l'enregistrement des notifications échoue
    Quand un événement "AnnoncePubliee" est reçu
    Alors le message n'est pas acquitté
    Et il est retenté 3 fois
    Et il est ensuite placé dans la DLQ (dead letter queue, file des messages en échec)
```

## Écran Angular minimal

Page **« Mes notifications »** en lecture seule (route protégée) : type (Confirmation / Alerte), texte, titre de l'annonce, date.
Un compteur dans l'en-tête indique le nombre de notifications.
Cet écran permet la démonstration de la tranche « publier → notifier » et le scénario E2E n° 2 (`cadrage.md` §1.3 point 12).
Le marquage « lue » est ajouté par US4.

## Notes

- La lecture `GET /notifications` est introduite ici sous une forme minimale (notifications du membre connecté, d'après le `sub` du jeton), car les tests d'acceptation en ont besoin ; US4 en complète les règles (décision validée le 27/10/2026).
- Garanties de livraison : ack après écriture en base, unicité `(membreId, annonceId, type)`, messages et files durables (`cadrage.md` §1.2 point 8).
- Le scénario DLQ relève plutôt d'un test d'intégration (Testcontainers RabbitMQ) que d'un test boîte noire.
- Hors périmètre : e-mail, push, WebSocket ; mise à jour des notifications d'une annonce retirée (`cadrage.md` §6).
