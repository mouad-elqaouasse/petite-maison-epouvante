# SQ-05 — RabbitMQ, comptes et connexion des services

| | |
|---|---|
| Dépend de | SQ-02 ; ADR 0010 (D5) |
| Sources | `cadrage.md` §1.2.8 (file quorum, `x-delivery-limit=3`, DLQ), §1.2.10 (un compte par service) ; ADR 0005 §3 (128/256 Mio, sonde `tcpSocket`) ; rapport exp. 2 §7-9 |

## But

Le broker est en place avec la topologie et les comptes au moindre privilège ; chaque service s'y connecte avec son compte.

## Contenu

- `deploy/app/rabbitmq/` : RabbitMQ 4.4 (image de l'exp. 2), 1 pod **128/256 Mio**, sonde de disponibilité
  **`tcpSocket` 5672** (pas de `rabbitmq-diagnostics`), non-root, plugin Prometheus (15692) actif.
- Définitions chargées au démarrage (D5) : exchange `annonces`, file quorum `notification.annonce-publiee`
  (`x-delivery-limit=3`, dead-letter vers la DLQ), DLQ ; comptes `troc` (écriture sur l'exchange seulement) et
  `notification` (lecture de sa file et de la DLQ) ; pas de compte `guest`.
  Le fichier de définitions (hachés des mots de passe) est produit sur le VPS par un script du dépôt
  (`deploy/scripts/`), placé dans un Secret ; les mots de passe en clair vont dans `troc-rabbitmq` et `notification-rabbitmq`.
- Services : `spring-boot-starter-amqp`, indicateur de santé `rabbit` ; connexion réessayée au démarrage.
  Aucun envoi ni consommation métier (US3).

## Critères d'acceptation

- Tests d'intégration (écrits d'abord, Testcontainers RabbitMQ ≥ 4.3) : santé `rabbit` UP.
- Sur le VPS : le compte `troc` ne peut pas lire la file ; le compte `notification` ne peut pas publier sur l'exchange.
- Suppression du pod RabbitMQ → comptes et topologie recréés au redémarrage ; services de nouveau UP sans redémarrage.
- RAM du pod au repos ≤ 130 Mio.
