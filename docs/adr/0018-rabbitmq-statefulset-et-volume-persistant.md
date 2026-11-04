# ADR 0018 — RabbitMQ en StatefulSet sur un volume persistant

- Statut : acceptée
- Date : 2026-11-04
- Complète : ADR 0010 ; **remplace sa dernière conséquence** (messages perdus à la recréation du pod)
- Source : ticket SQ-13 (`docs/tickets/SQ-13-rabbitmq-persistant.md`) ; `docs/cadrage.md` §1.2.8 ; rapport exp. 2 §7

## Contexte

- Livraison « au moins une fois » (`docs/cadrage.md` §1.2.8) : file quorum durable, messages persistants, publisher
  confirms. Avec le stockage `emptyDir`, un message confirmé mais pas encore consommé est perdu si le pod est recréé
  (capture `docs/soutenance/captures/sq-13/avant.txt` : 10 messages avant `kubectl delete pod`, 0 après).
- RabbitMQ range ses données sous le nom de son nœud Erlang (`mnesia/rabbit@<nom d'hôte>`) et la file quorum est liée à
  ce nœud. Avec un Deployment, le nom d'hôte est celui du pod (`rabbit@rabbitmq-58bf97d678-sxcfx`) : un volume seul ne
  suffit pas, le nouveau pod ignorerait les données de l'ancien.
- `local-path` (seule StorageClass du K3s mono-nœud, ADR 0002) est un dossier de l'hôte : `ReadWriteOnce` n'est vérifié
  que par nœud, deux pods du même nœud peuvent monter le même volume. `ReadWriteOncePod` n'existe que pour les pilotes
  CSI. La stratégie `Recreate` d'un Deployment ne joue qu'aux mises à jour : après un `kubectl delete pod`, le
  ReplicaSet crée le remplaçant sans attendre la fin de l'ancien.
- `local-path` a une politique `Delete` : supprimer le PVC efface le dossier, donc les données. Flux supprime
  (`prune: true`) tout objet qui disparaît du dépôt.

## Décision

1. RabbitMQ passe d'un Deployment à un **StatefulSet** `rabbitmq` (1 replica, `serviceName: rabbitmq`, le Service
   existant) : nom d'hôte stable `rabbitmq-0`, donc nœud `rabbit@rabbitmq-0` ; le contrôleur ne recrée `rabbitmq-0`
   qu'après la disparition complète de l'ancien pod, y compris après un `kubectl delete pod`.
2. **PVC `rabbitmq-data`** (`local-path`, `ReadWriteOnce`, 1 Gi) déclaré à part, comme `postgresql-data` (SQ-04), monté
   sur `/var/lib/rabbitmq` à la place de l'`emptyDir`.
3. **Annotation `kustomize.toolkit.fluxcd.io/prune: disabled`** sur `rabbitmq-data` et `postgresql-data` : Flux ne
   supprime jamais ces PVC, même retirés du dépôt. Leur suppression reste un geste manuel, avec l'accord du Lead Developer.
4. `load_definitions` (ADR 0010) conservé : comptes et topologie redéclarés à chaque démarrage, à l'identique.
5. Bascule : Deployment supprimé **avant** la création du StatefulSet (jamais deux RabbitMQ en même temps) ; coupure
   assumée, mesurée dans le ticket.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Deployment + nom de nœud fixe (`RABBITMQ_NODENAME=rabbit@localhost`) | nom stable, mais après `kubectl delete pod` deux pods montent brièvement le même volume (même nœud Erlang, mêmes fichiers) : risque de corruption |
| `volumeClaimTemplates` dans le StatefulSet | PVC créé hors du dépôt, donc invisible en revue et non annotable par Flux |
| Garder l'`emptyDir` (ADR 0010) | contraire à §1.2.8 dès qu'un consommateur réel existe (US3) |
| Opérateur RabbitMQ (cluster operator) | conçu pour plusieurs nœuds ; RAM d'un pod de plus pour un seul broker |

## Conséquences

- Les messages en attente survivent à la recréation du pod (capture `docs/soutenance/captures/sq-13/apres.txt`).
- Label `app: rabbitmq`, Service et ports inchangés : NetworkPolicies (ADR 0017), cibles Prometheus et tableaux de bord
  inchangés.
- Le nom de nœud dépend du nom du StatefulSet : le renommer rendrait les données invisibles (ADR à remplacer d'abord).
- Mono-nœud : le volume est sur le disque du VPS ; la perte du VPS reste une perte de messages (plan de remédiation).
- Perte possible entre le commit en base et la publication (outbox, plan de remédiation) : inchangée.
