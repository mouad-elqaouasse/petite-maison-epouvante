# ADR 0025 — Consommateur `AnnoncePubliee` : ack manuel, `basic.reject` et idempotence

- Statut : acceptée
- Date : 2026-11-06
- Source : US3, décisions D1, D2, D3 et D7 (validées le 06/11/2026) ; `docs/cadrage.md` §1.2 point 8 ;
  rapport exp. 2 §6 et §7 ; ADR 0020 (contrat)

## Contexte

- Livraison « au moins une fois » (`docs/cadrage.md` §1.2.8) : ack après écriture en base, unicité
  `(membreId, annonceId, type)`, 3 réessais (4 livraisons) puis DLQ (dead letter queue, file des messages en échec).
- Exp. 2 §6 : depuis RabbitMQ 4.3, `basic.nack` avec remise en file ne fait pas avancer le compteur comparé à
  `x-delivery-limit` ; le message boucle sans fin (746 livraisons en 10 s). Seul `basic.reject` le fait avancer.
- Spring AMQP, en acquittement automatique, peut rejeter par `basic.nack` ; un retry Spring suivi d'un rejet sans
  remise en file envoie le message en DLQ dès le premier rejet, sans passer par la limite de livraisons du broker.
- Exp. 2 §7 : les réessais sont immédiats ; une coupure de quelques secondes de la base enverrait tous les messages
  en DLQ en quelques millisecondes.

## Décision

1. **Acquittement manuel** (`AcknowledgeMode.MANUAL`) : le consommateur appelle lui-même `basicAck` **après le commit**
   de la transaction qui écrit les notifications (D1).
2. Tout échec (JSON illisible, version inconnue, catégorie inconnue, base indisponible) se termine par
   **`basicReject(deliveryTag, requeue=true)`**, jamais `basicNack` : le broker compte la livraison et, après la 4e,
   place le message dans `notification.annonce-publiee.dlq` (D2). Un message illisible suit donc la même règle qu'une
   panne de base : 4 livraisons, puis DLQ.
3. **Pause de 2 s avant chaque rejet** (`app.notification.pause-avant-rejet`), dans le fil du consommateur ; prefetch
   de 10 messages et un seul consommateur (D3). Une coupure de la base de moins de 6 s environ ne fait rien partir en
   DLQ.
4. **Idempotence** : `insert … on conflict (membre_id, annonce_id, type) do nothing` ; un événement livré deux fois
   (ack perdu après le commit, panne du consommateur) ne crée aucune notification de plus.
5. Destinataires calculés **à la consommation** : l'auteur reçoit `CONFIRMATION`, chaque membre abonné à la catégorie
   reçoit `ALERTE`, sauf l'auteur (scénario « Un auteur abonné à la catégorie reçoit seulement la confirmation »).
6. La colonne `lue` arrive avec US4 (marquage « lue »), pas avec US3 (D7).

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Acquittement automatique de Spring (`AUTO`) | rejet possible par `basic.nack`, qui boucle sans fin depuis RabbitMQ 4.3 (exp. 2) |
| Retry Spring puis `RejectAndDontRequeueRecoverer` | DLQ dès le premier rejet, `x-delivery-limit` ignoré ; réessais invisibles dans les métriques du broker |
| Message illisible rejeté sans remise en file (DLQ immédiate) | règle différente selon la cause ; le cadrage demande 4 livraisons puis DLQ |
| Réessais immédiats (sans pause) | une coupure courte de la base vide la file dans la DLQ (exp. 2 §7) |
| Délai par file d'attente intermédiaire (TTL + dead-lettering) | topologie plus lourde pour un POC ; la pause dans le fil suffit au volume visé |

## Conséquences

- Pendant une pause, les 9 autres messages du prefetch attendent : débit réduit seulement en cas d'échec.
- Un message en DLQ y reste : sa relecture est un geste d'administration (ADR 0026), hors périmètre V1.
- Tests (Testcontainers, RabbitMQ 4.4.0 avec la topologie réelle) : 4 livraisons puis DLQ pour un message illisible et
  pour une base coupée ; un même événement livré deux fois ne crée qu'une notification.
