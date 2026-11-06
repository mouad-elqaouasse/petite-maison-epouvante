# US3 — Être notifié d'une nouvelle annonce

| | |
|---|---|
| Service | `notification-service` (consommateur de `AnnoncePubliee`) |
| Priorité MoSCoW | **Must** (`cadrage.md` §5) |
| Dépendances | **US1a** (émission de `AnnoncePubliee`), **US2** (abonnements) et **SQ-13** (messages conservés au redémarrage de RabbitMQ, ajouté le 03/11/2026). |
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
    Et il est retenté 3 fois (4 livraisons au total)
    Et il est ensuite placé dans la DLQ (dead letter queue, file des messages en échec)
```

## Écran Angular minimal

Page **« Mes notifications »** en lecture seule (route protégée) : type (Confirmation / Alerte), texte, titre de l'annonce, date.
~~Un compteur dans l'en-tête indique le nombre de notifications.~~ Reporté à US4 (décision du 06/11/2026) : sans le
marquage « lue », il ne ferait que monter. L'en-tête porte seulement le lien « Mes notifications ».
Cet écran permet la démonstration de la tranche « publier → notifier » et le scénario E2E n° 2 (`cadrage.md` §1.3 point 12).
Le marquage « lue » est ajouté par US4.

## Notes

- La lecture `GET /notifications` est introduite ici sous une forme minimale (notifications du membre connecté, d'après le `sub` du jeton), car les tests d'acceptation en ont besoin ; US4 en complète les règles (décision validée le 27/10/2026).
- Garanties de livraison : ack après écriture en base, unicité `(membreId, annonceId, type)`, messages et files durables (`cadrage.md` §1.2 point 8).
- Le scénario DLQ relève plutôt d'un test d'intégration (Testcontainers RabbitMQ) que d'un test boîte noire.
  Il tourne sur la même version que le cluster (RabbitMQ 4.4.0, donc ≥ 4.3) : un message en échec doit être rejeté par
  `basic.reject` ou après un retry Spring, jamais par `basic.nack` avec remise en file, qui boucle sans fin depuis 4.3
  (`cadrage.md` §1.2 point 8 ; `docs/experimentations/exp2-rabbitmq-rapport.md` §6).
- Hors périmètre : e-mail, push, WebSocket ; mise à jour des notifications d'une annonce retirée (`cadrage.md` §6).

## Précisions validées le 06/11/2026 (ADR 0025 et 0026)

- Consommateur en **acquittement manuel** : `basicAck` après le commit ; tout échec (JSON illisible, version inconnue,
  base indisponible) → pause de 2 s puis `basicReject(requeue=true)`, jamais `basicNack` ; prefetch 10 (D1 à D3).
- « L'enregistrement des notifications échoue » est simulé par une coupure de la base (Toxiproxy) ; un message
  illisible suit la même règle (4 livraisons, puis DLQ). Preuve : en-tête `x-death` du message lu dans la DLQ.
- Compte RabbitMQ `notification` : lecture de sa file seulement, plus de la DLQ (D4, `cadrage.md` §1.2 point 10).
- Topologie et droits lus par les tests dans `deploy/scripts/rabbitmq-definitions.json`, le fichier utilisé sur le VPS (D5).
- Résilience (D6) : test Testcontainers (service arrêté, publication, redémarrage, la notification arrive) et
  démonstration sur le VPS (`scale` à 0, publication, `scale` à 1), capturée dans `docs/soutenance/captures/us3/`.
- Écran : bouton « Actualiser », date et heure (Europe/Paris), lien vers « Mes centres d'intérêt » si la liste est vide ;
  en-tête en grille de deux colonnes sous 48 rem (quatre liens ne tiennent plus à 375 px). Captures :
  `docs/soutenance/captures/us3-ui/`.
- `GET /api/notifications` : notifications du membre connecté, plus récentes d'abord ; champs `type`, `texte`,
  `annonceId`, `titreAnnonce`, `creeLe`. La colonne `lue` arrive avec US4 (D7).
- Tests : `NotifierNouvelleAnnonceAcceptationTest` (un test par scénario, même nom), `ConsommateurFiabiliteTest`
  (DLQ, idempotence, ack après commit, droits, résilience) ; écran : `notifications.spec.ts`.
