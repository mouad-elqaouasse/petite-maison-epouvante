# SQ-13 — Volume persistant pour RabbitMQ

| | |
|---|---|
| Dépend de | SQ-05 (RabbitMQ dans `app`) ; à faire **avant US3** (premier consommateur réel de `AnnoncePubliee`) |
| Sources | `cadrage.md` §1.2.8 (livraison « au moins une fois », messages et files durables) ; ADR 0010, Conséquences ; rapport exp. 2 §7 (« Pas de panne du broker : stockage `emptyDir` ») ; ticket SQ-07, Limites |
| MoSCoW | Must (proposé le 03/11/2026) |

## But

Les messages en attente survivent à un redémarrage du pod RabbitMQ.

## Constat (03/11/2026)

- RabbitMQ stocke ses données dans un `emptyDir` (`deploy/app/rabbitmq/rabbitmq.yaml`) : à chaque recréation du pod,
  toutes les files sont vidées. Les messages publiés et confirmés par le broker (publisher confirms) mais pas encore
  consommés sont **perdus**.
- C'est contraire à la garantie « au moins une fois » du cadrage (§1.2.8) : files quorum durables et messages persistants
  ne protègent rien si le disque disparaît avec le pod.
- ADR 0010 avait écarté le volume persistant (comptes déjà rechargés par `load_definitions`) et laissé cette perte au plan
  de remédiation. Ce ticket revient sur cette conséquence : **ADR 0016 à proposer**, qui remplace ce point de l'ADR 0010.

## Contenu

- `PersistentVolumeClaim` `rabbitmq-data` (`local-path`, `ReadWriteOnce`, taille à fixer, par exemple 1 Gi), monté sur
  `/var/lib/rabbitmq` à la place de l'`emptyDir`, comme `postgresql-data` (SQ-04). Stratégie `Recreate` conservée
  (volume monté par un seul pod).
- **Nom de nœud Erlang fixe** (`RABBITMQ_NODENAME`, par exemple `rabbit@localhost`) : avec un Deployment, le nom d'hôte
  change à chaque pod ; RabbitMQ range ses données sous `mnesia/rabbit@<hôte>` et les files quorum sont liées au nom du
  nœud. Sans nom fixe, le nouveau pod ne retrouverait ni ses données ni ses files. Alternative à comparer dans l'ADR :
  StatefulSet (nom d'hôte stable).
- `load_definitions` (ADR 0010) conservé : vérifier que l'import au démarrage sur des données existantes ne vide ni ne
  recrée les files (déclarations identiques).
- Permissions du volume pour l'utilisateur 100 / groupe 101 (`local-path` ignore `fsGroup`) : à vérifier au premier
  démarrage.
- `deploy/scripts/rabbitmq-verifier-persistance.sh` (adapté de `rabbitmq-verifier-droits.sh`).

## Test (écrit et lancé d'abord, il doit échouer avec l'`emptyDir`)

1. `troc` publie N messages persistants (`delivery_mode=2`), confirmés, sur `annonces` → file `notification.annonce-publiee`.
   `notification-service` arrêté pendant le test (0 replica), pour que les messages restent en attente.
2. Nombre de messages prêts dans la file : N.
3. Redémarrage du pod RabbitMQ (`kubectl rollout restart deploy/rabbitmq`), attente de la disponibilité.
4. Nombre de messages prêts dans la file : **toujours N** ; comptes `troc` et `notification` toujours valides.
5. Nettoyage : `notification` lit et acquitte les N messages ; `notification-service` remis à 1 replica.

Attendu avant le ticket : 0 message après redémarrage (écart). Attendu après : N.

## Critères d'acceptation

- Test ci-dessus conforme : N messages avant et après le redémarrage du pod.
- Santé `rabbit` UP dans les deux services après le redémarrage (SQ-05).
- Cibles RabbitMQ UP dans Prometheus (SQ-07) ; DLQ intacte (`notification.annonce-publiee.dlq`).
- RAM du nœud et disque utilisé mesurés (`kubectl top`, `free -m`, `df -h`) et consignés ici.
- ADR 0016 validée ; ADR 0010 et rapport exp. 2 annotés (renvoi vers l'ADR 0016).

## Vérification (04/11/2026)

Script : `deploy/scripts/rabbitmq-verifier-persistance.sh` (client pika `exp2-amqp:2`, label `app: troc-service` +
`sonde`, comme les sondes de SQ-08). Résultats : `docs/soutenance/captures/sq-13/`. Décisions : ADR 0018 (PR 33 et 34).

- **Test rouge** (`emptyDir`, `avant.txt`) : 10 messages avant `kubectl delete pod`, **0 après** ; nœud
  `rabbit@rabbitmq-58bf97d678-…` différent à chaque pod.
- **Test vert** (StatefulSet, `apres.txt`) : 10 messages avant, **10 après** ; nœud `rabbit@rabbitmq-0` ; comptes `troc` et
  `notification` acceptés ; nouveau pod prêt en 24 s ; nettoyage : 10 messages lus et acquittés. DLQ vide.
- **NetworkPolicies** (`matrice-apres-bascule.txt`) : matrice SQ-08, 0 écart ; label `app: rabbitmq` inchangé.
- **Santé et observabilité** (`verifications.txt`) : santé `rabbit` UP dans les deux services ; 8 cibles Prometheus UP,
  dont `rabbitmq` et `rabbitmq-files` ; 0 écart.
- **Mesures** (17:58 UTC) : nœud 2919 Mio utilisés (76 %), 819 Mio disponibles ; `rabbitmq-0` 82 Mio ; disque 54 % ;
  volume `rabbitmq-data` 520 Kio. RAM disponible jamais sous 819 Mio pendant le ticket.

### Écarts au ticket

- **ADR 0018** et non 0016 (numéro déjà pris par cAdvisor) ; **StatefulSet** plutôt que nom de nœud fixe : seul moyen
  d'avoir au plus un pod sur le volume après `kubectl delete pod` (`local-path` ne fait respecter `ReadWriteOnce` que par
  nœud). Test par `kubectl delete pod` et pas `rollout restart` (annulé par Flux, SQ-08).
- **PVC protégés** : annotation `kustomize.toolkit.fluxcd.io/prune: disabled` sur `rabbitmq-data` et `postgresql-data`.
- **Bascule faite par Flux** dès la fusion de la PR 33 (16:52:25 UTC), avant la suspension prévue : StatefulSet créé
  environ 1 s avant la suppression du Deployment, au lieu de l'ordre inverse demandé. Les deux pods ont coexisté environ
  25 s sans partager de données (ancien en `emptyDir`, file vide). Coupure AMQP : environ 24 s (16:52:26 → 16:52:50).
  Leçon : suspendre Flux **avant** la fusion (fait pour la PR 34).
- **Incident `fsGroup`** : l'hypothèse « `local-path` ignore `fsGroup` » était fausse. Le PV est de type `local` :
  Kubernetes applique `fsGroup` à chaque montage et a rendu le cookie Erlang lisible par le groupe ; au premier
  `kubectl delete pod` (16:58 UTC), RabbitMQ a refusé de démarrer (CrashLoopBackOff), `troc-service` n'était plus prêt.
  Rétabli par `chmod 600` sur le cookie (17:05 UTC), pod prêt à 17:10:39 : **panne d'environ 12 min**. Les 10 messages
  publiés avant la suppression étaient toujours là. Correctif durable (PR 34) : `fsGroupChangePolicy: OnRootMismatch`,
  `fsGroup: 101` conservé ; appliqué à 17:54 (pod recréé sans erreur, prêt en 23 s, 10 messages toujours présents), puis
  file vidée avant le test vert.

## Limites restantes

- Mono-nœud : le volume `local-path` est sur le disque du VPS ; la perte du VPS reste une perte de messages.
- Perte possible entre commit et publication (outbox, plan de remédiation) : inchangée.
