# Expérience 2 — RabbitMQ (version courte) : journal

- Date : 2026-10-29 ; namespace `sandbox` ; budget : 3 h maximum
- Fichiers : `experimentations/exp2/`
- Décision testée : livraison « au moins une fois » (`docs/cadrage.md` §1.2.8) et comptes au moindre privilège (§1.2.10)

## Protocole (validé le 2026-10-29)
- Clients de test en Python + pika (image `exp2-amqp:1` importée dans K3s sans registre).
- Topologie : exchange `annonces` (topic) → file quorum durable `notification.annonces` (`x-delivery-limit=3`,
  `x-dead-letter-exchange=annonces.dlx`, `x-dead-letter-strategy=at-least-once`) ; DLQ `notification.annonces.dlq`.
- « 3 réessais » = 4 livraisons au total, gérées par le broker (pas de délai entre les essais).
- Étapes : 0 état initial ; 1 mise en place ; 2 échange nominal (100 messages) ; 3 message poison → DLQ ;
  4 panne du consommateur pendant 1 000 envois ; 5 RAM de RabbitMQ ; 6 RAM de NATS au repos ; 7 nettoyage.
- Écarts avec le cadrage : « 3 tentatives » (§1.2.8) lu comme 3 réessais ; NATS mesuré en RAM seulement (§3.2, « Could »).

## Étape 0 — État initial
Commande (lecture seule) :
```powershell
ssh ubuntu@51.178.39.218 'date -u && kubectl -n sandbox get pods,jobs && kubectl -n sandbox get networkpolicy && kubectl -n sandbox top pods && kubectl top nodes && free -h && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name type messages && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_users && kubectl -n sandbox exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak'
```
Résultat (14:21:30 UTC) :
```
6 pods Running : grafana 295Mi, keycloak 542Mi, postgres 42Mi, prometheus 90Mi, rabbitmq 87Mi (122m CPU), whoami 5Mi
15 NetworkPolicies (refus-total, sortie-dns, … rabbitmq-entree-prometheus) ; aucun Job
nœud : 2171Mi (56 %) ; free : 1,5 Gio disponibles, swap 81 Mio
RabbitMQ : aucune file ; un seul compte (admin) ; memory.current 87 Mio, memory.peak 208 Mio
```
Constats :
- Point de départ propre : aucune file, aucun compte applicatif ; 1,5 Gio disponibles pour l'expérience.
- RabbitMQ au repos : 87 Mio, mais 122m de CPU (déjà vu en exp. 1) ; à garder en tête pour le budget CPU.

## Étape 1 — Mise en place
Commandes (poste Windows, Git Bash, puis VPS) :
```bash
docker build -t exp2-amqp:1 experimentations/exp2/app
docker save exp2-amqp:1 | gzip | ssh ubuntu@51.178.39.218 'gunzip | sudo k3s ctr images import -'
scp -r experimentations/exp2 ubuntu@51.178.39.218:~/exp2
ssh ubuntu@51.178.39.218 'cd ~/exp2 && bash 10-comptes.sh && kubectl apply -f 20-np-exp2.yaml -f 30-topologie-job.yaml && kubectl -n sandbox wait --for=condition=complete job/exp2-topologie --timeout=120s && kubectl -n sandbox logs job/exp2-topologie && kubectl apply -f 31-producteur.yaml -f 32-consommateur.yaml && kubectl -n sandbox rollout status deploy/exp2-producteur --timeout=120s && kubectl -n sandbox rollout status deploy/exp2-consommateur --timeout=120s && kubectl -n sandbox logs deploy/exp2-consommateur && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name type durable arguments messages consumers && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_bindings'
```
Résultat (14:31:37 UTC) :
```
image exp2-amqp:1 : 20,8 Mo, index sha256:81c6b6fb…bbcbd31, importée dans containerd de K3s (232 s, liaison montante lente)
user                configure  write          read
exp2-consommateur   ^$         ^$             ^notification\.annonces(\.dlq)?$
exp2-producteur     ^$         ^annonces$     ^$
topologie déclarée
notification.annonces      quorum  durable  x-delivery-limit=3, x-dead-letter-exchange=annonces.dlx,
                                            x-dead-letter-strategy=at-least-once, x-overflow=reject-publish   0 msg, 1 consommateur
notification.annonces.dlq  quorum  durable                                                                    0 msg
liaisons : annonces --annonce.publiee--> notification.annonces ; annonces.dlx --> notification.annonces.dlq
```
Constats :
- Topologie, droits et NetworkPolicies conformes au protocole ; le consommateur est connecté (1 consommateur sur la file).
- L'image de base `python:3.13-alpine` n'a pas été figée par un digest : acceptable pour un test jetable, pas pour `app`.

## Étape 2 — Échange nominal (100 messages) et droits
```bash
ssh ubuntu@51.178.39.218 'date -u && kubectl -n sandbox exec deploy/exp2-producteur -- python /app/producteur.py --run nominal --nb 100 && kubectl -n sandbox exec deploy/exp2-producteur -- python /app/producteur.py --run droits --mode lire; sleep 10; L=$(kubectl -n sandbox logs deploy/exp2-consommateur); echo "traitements: $(echo "$L" | grep -c "TRAITE run=nominal")"; echo "ids distincts: $(echo "$L" | grep "TRAITE run=nominal" | sed -E "s/.* id=([0-9]+) .*/\1/" | sort -un | wc -l)"; echo "relivraisons: $(echo "$L" | grep "run=nominal" | grep -c redelivered=True)"; echo "$L" | grep -E "connexion|prêt"; kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages messages_unacknowledged consumers'
```
Résultat (14:36:58 UTC) :
```
BILAN_PRODUCTEUR run=nominal envoyes=100 confirmes=100 non_confirmes=0 duree_s=27.9
lecture refusée : 403 ACCESS_REFUSED - read access to queue 'notification.annonces' in vhost '/' refused for user 'exp2-producteur'
traitements: 100 ; ids distincts: 100 ; relivraisons: 0
notification.annonces 0 message (0 non acquitté, 1 consommateur) ; notification.annonces.dlq 0
```
Constats :
- Échange conforme : 100 confirmés, 100 traités une fois, files vides ; le moindre privilège est effectif (403).
- **Écart : 27,9 s pour 100 messages, soit environ 280 ms par publication confirmée.** Le producteur attend la
  confirmation de chaque message avant d'envoyer le suivant (`confirm_delivery` de pika, mode bloquant).
  Hypothèse : une file quorum écrit chaque message sur disque et force la synchronisation (fsync) avant de confirmer ;
  le disque du VPS serait lent pour ce type d'écriture. Non vérifié à ce stade.
- Enjeu pour `app` : si `troc-service` attend la confirmation dans la requête, `POST /annonces` prend environ 280 ms
  de plus, à comparer au seuil p95 < 500 ms (`docs/cadrage.md` §1.3.11, indicateur 3). Pistes : confirmations
  asynchrones (Spring AMQP, `CorrelationData`) ou publication hors du fil de la requête.

## Étape 3 — Message poison : réessais puis DLQ
```bash
ssh ubuntu@51.178.39.218 'date -u && kubectl -n sandbox exec deploy/exp2-producteur -- python /app/producteur.py --run poison --nb 1 --poison 1 && sleep 10 && kubectl -n sandbox logs deploy/exp2-consommateur | grep "run=poison" && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages messages_unacknowledged && kubectl -n sandbox exec deploy/exp2-consommateur -- python /app/lire_dlq.py && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages'
```
Résultat (14:39:22 UTC) :
```
BILAN_PRODUCTEUR run=poison envoyes=1 confirmes=1 non_confirmes=0 duree_s=0.0
746 × ECHEC run=poison id=1 x-delivery-count=0 (1 × redelivered=False, 745 × redelivered=True) en ~10 s
notification.annonces 1 message (1 non acquitté) ; notification.annonces.dlq 0 ; BILAN_DLQ messages=0
```
**Écart : le message n'est jamais parti en DLQ ; il est relivré en boucle (≈ 75 fois par seconde) et le compteur reste à 0.**
- Cause (documentation RabbitMQ, « Quorum Queues », section sur la limite de livraison) : depuis RabbitMQ 4.3, la limite
  est comparée à `delivery-count`, qui n'augmente que sur un vrai échec : `basic.reject` ou perte de la connexion avec des
  messages non acquittés. `basic.nack` avec remise en file n'augmente que `acquired-count` (relivraisons illimitées voulues).
  Notre consommateur utilisait `basic.nack` ; l'image est en 4.4.0.
- Correction : `basic_reject(requeue=True)` dans `consommateur.py`, image `exp2-amqp:2`.
- **Enjeu pour `app`** : Spring AMQP rejette par défaut un message en échec avec `basicNack` (à vérifier) ; avec
  RabbitMQ ≥ 4.3, la DLQ ne serait jamais atteinte et un message poison bouclerait. À couvrir par un test
  Testcontainers dans US3 (retry côté Spring puis rejet sans remise en file, ou `basic.reject`).
- Autre observation : 1 message seul est confirmé en moins de 0,1 s, alors que l'étape 2 donnait 280 ms par message ;
  l'hypothèse du fsync systématique est donc fragile. À mesurer à l'étape 5.

### Étape 3a — Arrêt de la boucle (14:41:49 UTC)
```bash
ssh ubuntu@51.178.39.218 'kubectl -n sandbox scale deploy/exp2-consommateur --replicas=0 && kubectl -n sandbox wait --for=delete pod -l app=exp2-consommateur --timeout=60s; kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q purge_queue notification.annonces && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages consumers && kubectl -n sandbox top pod -l app=rabbitmq'
```
Consommateur arrêté, message poison purgé ; files à 0 ; RabbitMQ 98m CPU, 91 Mio.

### Étape 3b — Correction (`basic.reject`, image `exp2-amqp:2`) et nouvel essai
```bash
docker build -t exp2-amqp:2 experimentations/exp2/app && docker save exp2-amqp:2 | gzip | ssh ubuntu@51.178.39.218 'gunzip | sudo k3s ctr images import -' && scp experimentations/exp2/3*.yaml ubuntu@51.178.39.218:~/exp2/ && scp experimentations/exp2/app/consommateur.py ubuntu@51.178.39.218:~/exp2/app/
ssh ubuntu@51.178.39.218 'cd ~/exp2 && kubectl apply -f 31-producteur.yaml -f 32-consommateur.yaml && kubectl -n sandbox scale deploy/exp2-consommateur --replicas=1 && kubectl -n sandbox rollout status deploy/exp2-producteur --timeout=120s && kubectl -n sandbox rollout status deploy/exp2-consommateur --timeout=120s && sleep 5 && kubectl -n sandbox exec deploy/exp2-producteur -- python /app/producteur.py --run poison2 --nb 1 --poison 1 && sleep 10 && kubectl -n sandbox logs deploy/exp2-consommateur | grep "run=poison2" | head -20 && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages messages_unacknowledged && kubectl -n sandbox exec deploy/exp2-consommateur -- python /app/lire_dlq.py && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues name messages'
```
Résultat (14:46:51 UTC ; image :2 index sha256:594954e3…5e3b10, import en 255 s) :
```
ECHEC run=poison2 id=1 x-delivery-count=0 redelivered=False
ECHEC run=poison2 id=1 x-delivery-count=1 redelivered=True
ECHEC run=poison2 id=1 x-delivery-count=2 redelivered=True
ECHEC run=poison2 id=1 x-delivery-count=3 redelivered=True
notification.annonces 0 ; notification.annonces.dlq 1
DLQ run=poison2 id=1 x-delivery-count=4 x-first-death-reason=delivery_limit
    x-death=[{count: 1, reason: delivery_limit, queue: notification.annonces, exchange: annonces, routing-keys: [annonce.publiee]}]
BILAN_DLQ messages=1 ; files à 0 après lecture
```
Constats :
- Conforme : 1 livraison + 3 réessais, puis DLQ avec la raison `delivery_limit` et l'origine du message (file, exchange, clé).
- Les en-têtes `x-death` suffisent pour diagnostiquer un message en DLQ (alerte « DLQ > 0 », `docs/cadrage.md` §1.4.16).

## Étape 4 — Panne du consommateur pendant l'envoi
### Essai 1 (invalide)
```bash
scp experimentations/exp2/40-panne.sh ubuntu@51.178.39.218:~/exp2/ && ssh ubuntu@51.178.39.218 'cd ~/exp2 && bash 40-panne.sh 1000 panne'
```
Résultat (14:49:20 UTC) :
```
BILAN_PRODUCTEUR run=panne envoyes=1000 confirmes=1000 non_confirmes=0 duree_s=5.9
panne 14:49:51 (T+30 s) ; redémarrages : 1 ; traités avant panne : 1000, après : 0 ; manquants : 0 ; doublons : 0
memory.peak RabbitMQ : 214 Mio avant, 270 Mio après ; relevés memory.current : 94 Mio
```
**Écarts :**
- Test invalide : les 1 000 messages ont été envoyés en 5,9 s (≈ 6 ms par message) et traités avant la panne.
  Le consommateur n'a rien perdu, mais aucun message n'était en jeu au moment de l'arrêt.
- Les 280 ms par message de l'étape 2 ne se reproduisent pas : ce n'était pas un coût fixe des confirmations.
  Cause non établie (premières écritures de la file quorum ? démarrage des pods ?) ; la latence sera suivie en charge.
- `memory.peak` passe de 214 à 270 Mio alors que `memory.current` reste à 94 Mio. Hypothèse : `rabbitmqctl`, appelé
  toutes les 5 s par le relevé, lance une seconde VM Erlang dans le conteneur, comptée dans sa RAM. La sonde de
  disponibilité (`rabbitmq-diagnostics`, toutes les 20 s) fait de même. À vérifier à l'étape 5 : enjeu direct pour la
  limite de 256 Mio prévue (`docs/cadrage.md` §1.4.18).

Corrections de `40-panne.sh` : débit fixé à 20 msg/s, panne à T+15 s, relevé toutes les 2 s sans `rabbitmqctl`
(`memory.current` et endpoint Prometheus local). Consommateur ralenti (`TRAITEMENT_MS=100`, 10 msg/s) pour que
10 messages livrés non acquittés (prefetch) soient en jeu au moment de la panne.

### Essai 2 (valide)
```bash
scp 40-panne.sh 32-consommateur.yaml ubuntu@51.178.39.218:~/exp2/ && ssh ubuntu@51.178.39.218 'cd ~/exp2 && kubectl apply -f 32-consommateur.yaml && kubectl -n sandbox rollout status deploy/exp2-consommateur --timeout=120s && sleep 5 && bash 40-panne.sh 1000 panne2 20 15'
```
Résultat (14:54:39 UTC ; panne 14:54:55, fin des envois 14:55:32, file vide 14:56:32) :
```
BILAN_PRODUCTEUR run=panne2 envoyes=1000 confirmes=1000 non_confirmes=0 duree_s=50.0
redémarrages : 1 ; traités avant panne : 131, après : 869 ; ids distincts : 1000 ; manquants : 0 ; doublons : 0
relivrés : 10 (x-delivery-count max = 1) ; files : 0 / DLQ 0
relevés (prêts / non acquittés / RAM) : 113/10/95 Mio → 500/10/96 Mio (fin des envois) → 0/0/95 Mio ; max relevé 158 Mio
```
Constats :
- **Aucune perte** : 1 000 confirmés, 1 000 traités ; les 10 messages en cours (prefetch) ont été relivrés une fois.
- 0 doublon ici (les 10 messages n'avaient pas encore été traités), mais un doublon reste possible : idempotence nécessaire.
- Une panne compte comme un échec (`x-delivery-count` = 1) : 3 pannes successives sur un même message l'enverraient en DLQ.
- RAM stable à 95 Mio avec 500 messages en attente ; pics isolés (125, 147, 158 Mio), compatibles avec la sonde
  `rabbitmq-diagnostics` (toutes les 20 s) : vérifié à l'étape 5.

## Étape 5 — RAM de RabbitMQ
```bash
scp experimentations/exp2/50-ram.sh ubuntu@51.178.39.218:~/exp2/ && ssh ubuntu@51.178.39.218 'cd ~/exp2 && bash 50-ram.sh'
```
Résultat (14:59:45 UTC ; `memory.current` toutes les 0,5 s ; détail dans `~/exp2/resultats/ram-*.txt` sur le VPS) :
```
A. repos 60 s           : médiane 95 Mio ; pics à 157-159 Mio pendant ~4 s à 14:59:55, 15:00:15, 15:00:35 (toutes les 20 s)
B. rabbitmqctl à T+5 s  : médiane 96 Mio ; max 193 Mio (CLI + sonde superposées)
C. 10 000 × 1 Ko        : BILAN confirmes=10000 non_confirmes=0 duree_s=40.6 (≈ 246 msg/s confirmés un par un)
                          palier 95 → 126 Mio file pleine ; pics de sonde à 184-190 Mio
   memory_breakdown     : reserved_unallocated 51 Mo, code 24, other_system 20, other_proc 16, binary 11, quorum_ets 9
   kubectl top          : 412m CPU, 167Mi ; memory.peak 270 Mio (essai 1 de l'étape 4) ; oom_kill 0
nœud après purge        : 1,5 Gio disponibles, swap 81 Mio
```
Constats :
- **Hypothèse confirmée** : chaque passage de la sonde `rabbitmq-diagnostics` (seconde VM Erlang dans le conteneur)
  ajoute ≈ 60 Mio pendant ~4 s ; un `rabbitmqctl` en plus porte le pic à ≈ 190 Mio, jusqu'à 270 Mio sous charge.
- Le broker seul : 95 Mio au repos, 126 Mio avec 10 000 messages (≈ 10 Mo de données) en attente.
- **Écart avec le budget** (`docs/cadrage.md` §1.4.18, « 120 Mio, à confirmer ») : ≈ 130 Mio sans sonde CLI,
  ≈ 190 Mio avec, 270 Mio observés au pire ; une limite de 256 Mio serait dépassée avec la sonde actuelle.
- Proposition pour `app` (à valider) : sonde `tcpSocket` sur 5672 au lieu de `rabbitmq-diagnostics` ; requests/limits
  128 / 256 Mio ; budget au pic 130 Mio ; commandes `rabbitmqctl` réservées au diagnostic ponctuel.

## Étape 6 — NATS : RAM au repos
```bash
scp experimentations/exp2/60-nats.yaml ubuntu@51.178.39.218:~/exp2/ && ssh ubuntu@51.178.39.218 'cd ~/exp2 && date -u && kubectl apply -f 60-nats.yaml && kubectl -n sandbox rollout status deploy/nats --timeout=180s && kubectl -n sandbox logs deploy/nats | grep -iE "version|jetstream|max memory" | head -6 && sleep 120 && date -u && kubectl -n sandbox exec deploy/nats -- sh -c "i=0; while [ \$i -lt 60 ]; do echo \$((\$(cat /sys/fs/cgroup/memory.current) / 1048576)); sleep 0.5; i=\$((i+1)); done" | sort -n | awk "{a[NR]=\$1} END{print \"min=\" a[1], \"mediane=\" a[int((NR+1)/2)], \"max=\" a[NR]}" && kubectl -n sandbox exec deploy/nats -- cat /sys/fs/cgroup/memory.peak && kubectl -n sandbox exec deploy/nats -- wget -qO- http://localhost:8222/varz | grep -E "\"mem\"|\"cpu\"" && kubectl -n sandbox top pod -l app=nats && kubectl -n sandbox top pod -l app=rabbitmq && kubectl delete -f 60-nats.yaml && kubectl -n sandbox wait --for=delete pod -l app=nats --timeout=60s && free -h'
```

Résultat (déployé 15:05:07 UTC, mesuré 15:07:12 UTC, puis supprimé) :
```
Version: 2.15.2 ; JetStream démarré, Max Memory 61.04 MB, stockage /data/jetstream
memory.current (cgroup) : min 3, médiane 4, max 4 Mio ; memory.peak 8,7 Mio
/varz : "mem" 15 597 568 (≈ 15 Mio, mémoire résidente du processus) ; kubectl top : 8m CPU, 3Mi
même instant, RabbitMQ : 135m CPU, 99Mi
```
Constats :
- NATS + JetStream au repos : 4 à 15 Mio selon la mesure, contre 95 Mio pour RabbitMQ (6 à 25 fois moins) ; CPU 8m contre 135m.
- L'écart entre cgroup (4 Mio) et `/varz` (15 Mio) vient probablement des pages du binaire, partagées et comptées ailleurs.
- Mesure au repos seulement : ni débit, ni rétention, ni équivalent du retry/DLQ testés (pas de DLQ native dans JetStream).

## Étape 7 — Nettoyage
```bash
ssh ubuntu@51.178.39.218 'cd ~/exp2 && date -u && kubectl delete -f 31-producteur.yaml -f 32-consommateur.yaml -f 20-np-exp2.yaml && kubectl -n sandbox delete job exp2-topologie --ignore-not-found && kubectl -n sandbox delete secret exp2-producteur exp2-consommateur && kubectl -n sandbox wait --for=delete pod -l groupe=exp2 --timeout=60s; kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q delete_queue notification.annonces && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q delete_queue notification.annonces.dlq && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q delete_user exp2-producteur && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q delete_user exp2-consommateur && sudo k3s ctr images rm docker.io/library/exp2-amqp:1 docker.io/library/exp2-amqp:2 && kubectl -n sandbox get pods,jobs,secrets && kubectl -n sandbox get networkpolicy | grep -c . && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_queues && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmqctl -q list_users && kubectl -n sandbox top pods && free -h'
```
Résultat (15:09:37 UTC) : pods, Job, Secrets, NetworkPolicies, files, comptes et images de l'expérience 2 supprimés ;
retour à l'état initial (6 pods, 15 NetworkPolicies, compte `admin` seul) ; 1,5 Gio disponibles, swap 81 Mio.
Conservés : RabbitMQ et le reste de `sandbox` (expérience 3), `~/exp2` sur le VPS (relevés bruts dans `resultats/`),
les exchanges vides `annonces` et `annonces.dlx` (disparaîtront avec le pod RabbitMQ, stockage `emptyDir`).
RabbitMQ relevé à 470m CPU / 162Mi juste après les commandes `rabbitmqctl` du nettoyage (effet CLI vu à l'étape 5).

### Rapport final
Rédigé : `docs/experimentations/exp2-rabbitmq-rapport.md` (2026-10-29). **Expérience 2 terminée.**
