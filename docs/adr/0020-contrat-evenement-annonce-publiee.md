# ADR 0020 — Contrat de l'événement `AnnoncePubliee`, version 1

- Statut : acceptée
- Date : 2026-11-05
- Source : US1a, décision D3 (validée le 05/11/2026, avec le champ `version` et `annonceId` comme clé anti-doublon) ;
  `docs/cadrage.md` §1.2 points 7, 8 et 10 ; ADR 0010 ; exp. 2

## Contexte

- `troc-service` publie l'événement sans connaître les destinataires ; `notification-service` le consomme (US3), avec
  une livraison « au moins une fois » et une idempotence par `(membreId, annonceId, type)` (`cadrage.md` §1.2.8).
- Le compte RabbitMQ `troc` ne peut qu'écrire sur l'exchange `annonces` (ADR 0010).
- Les deux services évolueront séparément : le format doit pouvoir changer sans casser le consommateur.

## Décision

1. Contrat décrit dans `docs/schemas/evenement-annonce-publiee.md` : JSON avec `version: 1`, `annonceId`, `categorie`
   (libellé), `auteurId`, `titre`, `creeLe`, `photoUrl` ; message persistant, `message_id` = `annonceId`,
   `type` = `AnnoncePubliee`, en-tête `correlationId`, pas d'en-tête de type Java.
2. `annonceId` est la **clé anti-doublon** du consommateur.
3. Émission **après le commit** (`@TransactionalEventListener(AFTER_COMMIT)`), l'appel à Cloudinary restant hors de la
   transaction ; **publisher confirm** attendu de façon synchrone (5 s au plus) et `mandatory` (retour si non routé).
4. Un échec d'émission ne fait pas échouer la publication déjà enregistrée : journalisé et compté
   (`annonces_publiees_emission_echecs_total`).

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Sérialisation Java par défaut de Spring AMQP (en-tête `__TypeId__`) | lie le consommateur aux classes du producteur |
| Pas de champ de version | un changement de format ne pourrait pas être détecté par le consommateur |
| Confirmations asynchrones | plus de code ; à reconsidérer si la latence de la confirmation pèse sur `POST /annonces` (indicateur 3, décision du 29/10/2026) |
| Outbox transactionnelle | supprime la perte entre commit et publication, mais hors périmètre V1 (`cadrage.md` §6) |

## Conséquences

- Perte possible d'un événement entre commit et publication (broker coupé à ce moment) : visible par la métrique,
  limite assumée, outbox au plan de remédiation (`cadrage.md` §8).
- La latence de la confirmation s'ajoute à celle de `POST /annonces` : mesurée pendant les campagnes de charge.
- Tant que US3 n'est pas livrée, les messages s'accumulent dans `notification.annonce-publiee` (volume faible, volume
  persistant depuis SQ-13).
