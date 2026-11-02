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

## Résultats (02/11/2026) — ticket clos

- **PR [#18](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/18)** : tous les contrôles verts après
  correction ; livrée par Flux (révision `e215e8d`, images `4d8c2e3`).
- **Écart corrigé en CI** : Trivy a bloqué `construire` (4 CVE HIGH sur `com.rabbitmq:amqp-client` 5.30.0, version de
  Spring Boot 4.1.2 : CVE-2026-63337, -69219, -69220, -75516). Version forcée à **5.34.0** (`rabbit-amqp-client.version`
  dans les deux `pom.xml`), à retirer quand Spring Boot embarquera une version corrigée.
- **Tests d'intégration** (Testcontainers RabbitMQ 4.4.0 + Toxiproxy), 3 par service, en échec (404 sur
  `/actuator/health/rabbit`) puis verts en local et en CI : santé `rabbit` UP ; broker absent au démarrage → le service
  démarre (`readiness` 503, `liveness` 200) puis passe UP ; broker coupé en fonctionnement → `readiness` 503,
  `liveness` 200, puis 200. `rabbit` est dans `readiness`, pas dans `liveness` (même règle que `db`, ADR 0013).
  Les autres tests désactivent l'indicateur `rabbit` (configuration de test commune) ; aucun test existant modifié.
- **Secrets** `troc-rabbitmq`, `notification-rabbitmq`, `rabbitmq-definitions` créés dans `app` par
  `deploy/scripts/rabbitmq-secrets.sh` (`openssl rand -hex 24`, hachés SHA-256 salés au format RabbitMQ, jamais affichés).
- **Broker** : RabbitMQ 4.4.0, uid 100, système de fichiers en lecture seule, définitions chargées au démarrage
  (« Will not seed default virtual host and user ») : **aucun compte `guest`**, aucun compte administrateur.
- **Droits sur le VPS** (`deploy/scripts/rabbitmq-verifier-droits.sh`, image `exp2-amqp:2` reconstruite depuis
  `experimentations/exp2/app`, importée dans K3s puis supprimée) :
  - `troc` publie sur `annonces` (confirmé, routé) ; lecture de la file refusée (`403 ACCESS_REFUSED`) ;
  - `notification` lit et acquitte le message, lit la DLQ ; publication sur `annonces` refusée (`403 ACCESS_REFUSED`).
- **Suppression du pod RabbitMQ** (15:39:41 UTC) : nouveau pod prêt en moins de 20 s ; santé `rabbit` absente à
  15:40:04 (services retirés du Service, `readiness` DOWN), UP dans les deux services à 15:40:09 ; **aucun redémarrage**
  des services. Comptes, droits, exchanges `annonces` (topic) et `annonces.dlx` (fanout), file quorum
  `notification.annonce-publiee` (`x-delivery-limit=3`, dead-letter vers `annonces.dlx`) et DLQ recréés à l'identique.
- **RAM** (`kubectl top`, 02/11/2026) : RabbitMQ **99 Mio** au repos (cible ≤ 130 Mio ; pic au démarrage 143 Mio,
  sous la limite de 256 Mio) ; `troc-service` 170 Mio, `notification-service` 169 Mio, PostgreSQL 61 Mio ;
  nœud 1 971 Mio (51 %) ; `free -m` : 1 753 Mio disponibles, swap 73 Mio. Chaque appel à `rabbitmqctl` ajoute
  ≈ 40 Mio pendant quelques secondes (141 Mio juste après le diagnostic), comme mesuré à l'exp. 2.
  Nouveau pod, 5 min après sa création (15:44:52 UTC) : **86 Mio**, pic du cgroup 163 Mio (diagnostic `rabbitmqctl`
  compris) ; nœud 1 988 Mio (52 %), 1 724 Mio disponibles, swap 73 Mio.
- **Nettoyage** : image `exp2-amqp:2` supprimée du nœud, dossier `~/sq05` supprimé.

## À reprendre dans US3 (leçon de l'expérience 2)

Un message en échec est rejeté par **`basic.reject`** (remise en file, ou retry Spring puis rejet sans remise en file),
**jamais par `basic.nack`** : depuis RabbitMQ 4.3, `nack` ne fait pas avancer le compteur comparé à `x-delivery-limit`
et le message boucle sans fin au lieu d'aller en DLQ (746 livraisons en 10 s pendant l'exp. 2).
Sources : `docs/cadrage.md` §1.2.8 ; `docs/experimentations/exp2-rabbitmq-rapport.md` §6, §9 et §10.
Le test Testcontainers « message en échec → DLQ après 4 livraisons » (RabbitMQ ≥ 4.3) est prévu dans US3.
