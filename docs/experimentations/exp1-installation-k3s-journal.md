# Expérience 1 — Socle K3s (journal brut)

Journal des commandes et résultats, base du rapport de l'expérience 1.
Toutes les commandes sont lancées depuis le poste Windows via `ssh ubuntu@51.178.39.218 "..."`.

## Avancement

Périmètre de l'expérience 1 : `docs/cadrage.md` §3, point 1 « Socle K3s ».

| Partie | Statut |
|---|---|
| 1. Installation de K3s (UFW, K3s, kubectl, vérifications) | **Fait** (2026-10-28) |
| 2. RAM réelle de chaque composant de la stack | **Fait** (2026-10-28), hors JVM Spring Boot (cert-manager mesuré en partie 3) |
| 3. Traefik + cert-manager + Let's Encrypt (staging puis production) | **Fait** (2026-10-28) |
| 4. Keycloak (issuer public / JWKS interne) | À faire |
| 5. HPA avec metrics-server (1 → 3 replicas sous charge, RAM consommée) | À faire |
| 6. Application des NetworkPolicies | À faire |

Le détail des parties restantes est en fin de journal.

## Contexte de départ (déjà fait avant l'expérience)
- Menuly arrêté, Docker désactivé, swap de 2 Go, Ubuntu 24.04.
- Mesure de départ annoncée : 513 Mo de RAM utilisés par le système seul.

## Étape 0 — État initial (2026-10-28)

Commande :
```bash
ssh ubuntu@51.178.39.218 "hostname; lsb_release -ds; uname -r; nproc; free -h; swapon --show; sudo -n true && echo SUDO_SANS_MOT_DE_PASSE_OK; sudo ufw status verbose; systemctl is-active docker; systemctl is-enabled docker; sudo ss -tlnp | grep -E ':(80|443|6443)\s' || echo 'ports libres'"
```

Résultat :
```
vps-c67c8a44
Ubuntu 24.04.4 LTS
6.8.0-142-generic
2                      (vCPU)

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       569Mi       1.1Gi       1.1Mi       2.3Gi       3.2Gi
Swap:          2.0Gi          0B       2.0Gi

NAME      TYPE SIZE USED PRIO
/swapfile file   2G   0B   -2

SUDO_SANS_MOT_DE_PASSE_OK

Status: active
Logging: on (low)
Default: deny (incoming), allow (outgoing), deny (routed)
22/tcp (OpenSSH)  ALLOW IN  Anywhere   (+ v6)
80                ALLOW IN  Anywhere   (+ v6)
443               ALLOW IN  Anywhere   (+ v6)

Docker : inactive / disabled
Ports 80, 443, 6443 : libres
```

Constats :
- RAM utilisée avant K3s : **569 Mio** (513 Mo annoncés lors de la mesure précédente ; écart dû aux variations normales du système).
- 2 vCPU, 3,7 Gio de RAM visibles, swap de 2 Gio inutilisé.
- UFW actif ; 80 et 443 déjà ouverts (utiles pour Traefik) ; 6443 non exposé.
- Version stable de K3s au moment de l'expérience : `v1.36.5+k3s1` (canal `stable` de update.k3s.io).

## Étape 1 — Règles UFW pour les réseaux K3s

Commande :
```bash
ssh ubuntu@51.178.39.218 "sudo ufw allow from 10.42.0.0/16 to any comment 'k3s pods'; sudo ufw allow from 10.43.0.0/16 to any comment 'k3s services'; sudo ufw status numbered"
```

Résultat :
```
Rule added
Rule added
Status: active

     To                         Action      From
     --                         ------      ----
[ 1] OpenSSH                    ALLOW IN    Anywhere
[ 2] 80                         ALLOW IN    Anywhere
[ 3] 443                        ALLOW IN    Anywhere
[ 4] Anywhere                   ALLOW IN    10.42.0.0/16               # k3s pods
[ 5] Anywhere                   ALLOW IN    10.43.0.0/16               # k3s services
[ 6] OpenSSH (v6)               ALLOW IN    Anywhere (v6)
[ 7] 80 (v6)                    ALLOW IN    Anywhere (v6)
[ 8] 443 (v6)                   ALLOW IN    Anywhere (v6)
```

Constats :
- Réseau des pods (10.42.0.0/16) et des services (10.43.0.0/16) autorisés en entrée.
- Aucun port supplémentaire exposé sur Internet ; 6443 (API Kubernetes) reste fermé de l'extérieur.

## Étape 2 — Installation de K3s

Commande (lancée manuellement par moi depuis mon terminal : le mode automatique de Claude Code
bloque l'exécution d'un script téléchargé via `curl | sh`) :
```bash
ssh ubuntu@51.178.39.218 "curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION='v1.36.5+k3s1' sh -; systemctl is-active k3s; k3s --version; free -h"
```

Résultat relevé à la fin de l'installation :
- Service `k3s` : `active`
- Version : `v1.36.5+k3s1`
- RAM disponible : 3.0 GiB
- Swap utilisé : 0 B

Vérification indépendante, ~1 min 30 après le démarrage du service (12:54:40 UTC) :
```
k3s : active / enabled
k3s version v1.36.5+k3s1 (3dd98cc5), go1.26.8

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.2Gi       110Mi       2.1Mi       2.7Gi       2.5Gi
Swap:          2.0Gi          0B       2.0Gi

NAME           STATUS   ROLES           AGE   VERSION        INTERNAL-IP     CONTAINER-RUNTIME
vps-c67c8a44   Ready    control-plane   82s   v1.36.5+k3s1   51.178.39.218   containerd://2.3.4-k3s1.36

NAMESPACE     NAME                                      READY   STATUS      RESTARTS      AGE
kube-system   coredns-7cfb7bc9c7-6zlvb                  1/1     Running     0             88s
kube-system   helm-install-traefik-5vjjz                0/1     Completed   2 (73s ago)   88s
kube-system   helm-install-traefik-crd-kqsfk            0/1     Completed   0             88s
kube-system   local-path-provisioner-77b9867795-hchv5   1/1     Running     0             88s
kube-system   metrics-server-6dc596dfb8-drjwb           1/1     Running     0             88s
kube-system   svclb-traefik-bd3a316e-spx9h              2/2     Running     0             60s
kube-system   traefik-7c8544f77-5mc87                   1/1     Running     0             60s
```

État de kubectl après installation :
- `/usr/local/bin/kubectl` -> lien vers `k3s` (fourni par K3s).
- `/etc/rancher/k3s/k3s.yaml` : `-rw------- root root` → `kubectl` sans sudo échoue (`permission denied`).
- Pas de `~/.kube/`, pas de variable `KUBECONFIG`.

Constats :
- RAM utilisée : 569 Mio avant K3s → **1,2 Gio** juste après (≈ +650 Mio pour K3s et ses composants
  par défaut). Disponible : 2,5 Gio à cette mesure (3,0 Gio relevés en fin d'installation, avant le
  démarrage de tous les pods). Mesure à refaire une fois le cluster stabilisé.
- Les 2 redémarrages de `helm-install-traefik` sont normaux : le job attend que les CRD de Traefik
  soient installées, puis se termine (`Completed`).

## Étape 3 — Configuration de kubectl pour l'utilisateur ubuntu

Commande (depuis PowerShell : l'accent grave empêche PowerShell d'interpréter `$HOME`) :
```powershell
ssh ubuntu@51.178.39.218 "mkdir -p ~/.kube && sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config && sudo chown ubuntu:ubuntu ~/.kube/config && chmod 600 ~/.kube/config && (grep -q 'export KUBECONFIG=' ~/.bashrc || sed -i '1i export KUBECONFIG=`$HOME/.kube/config' ~/.bashrc) && ls -l ~/.kube/config && head -n 1 ~/.bashrc"
```

Résultat :
```
-rw------- 1 ubuntu ubuntu 2941 Oct 28 13:00 /home/ubuntu/.kube/config
export KUBECONFIG=$HOME/.kube/config
```

Vérification :
```powershell
ssh ubuntu@51.178.39.218 "echo KUBECONFIG=`$KUBECONFIG; kubectl get nodes"
```
```
KUBECONFIG=/home/ubuntu/.kube/config
NAME           STATUS   ROLES           AGE     VERSION
vps-c67c8a44   Ready    control-plane   5m28s   v1.36.5+k3s1
```

Choix et constats :
- Copie privée (`600`, propriétaire ubuntu) plutôt que `--write-kubeconfig-mode 644`, qui rendrait le
  fichier d'administration lisible par tous les utilisateurs du VPS. `/etc/rancher/k3s/k3s.yaml` reste en `600 root`.
- L'export est placé en **première ligne** de `~/.bashrc` : le `.bashrc` d'Ubuntu s'arrête tout de suite pour
  les shells non interactifs (`ssh hôte "commande"`), une ligne ajoutée en fin de fichier ne serait pas lue.
- Sans `KUBECONFIG`, le kubectl de K3s lit toujours `/etc/rancher/k3s/k3s.yaml`, même si `~/.kube/config` existe.
- `~/.kube/config` contient un certificat d'administration du cluster : jamais affiché, jamais copié dans le dépôt.

## Étape 4 — Vérifications finales (cluster démarré depuis ~6 min)

Commande :
```bash
ssh ubuntu@51.178.39.218 "date -u; kubectl get nodes -o wide; kubectl get pods -A; kubectl top nodes; kubectl top pods -A; free -h"
```

Résultat :
```
Wed Oct 28 13:00:57 UTC 2026

NAME           STATUS   ROLES           AGE     VERSION        INTERNAL-IP     CONTAINER-RUNTIME
vps-c67c8a44   Ready    control-plane   6m16s   v1.36.5+k3s1   51.178.39.218   containerd://2.3.4-k3s1.36

NAMESPACE     NAME                                      READY   STATUS      RESTARTS        AGE
kube-system   coredns-7cfb7bc9c7-6zlvb                  1/1     Running     0               6m11s
kube-system   helm-install-traefik-5vjjz                0/1     Completed   2 (5m56s ago)   6m11s
kube-system   helm-install-traefik-crd-kqsfk            0/1     Completed   0               6m11s
kube-system   local-path-provisioner-77b9867795-hchv5   1/1     Running     0               6m11s
kube-system   metrics-server-6dc596dfb8-drjwb           1/1     Running     0               6m11s
kube-system   svclb-traefik-bd3a316e-spx9h              2/2     Running     0               5m43s
kube-system   traefik-7c8544f77-5mc87                   1/1     Running     0               5m43s

NAME           CPU(cores)   CPU(%)   MEMORY(bytes)   MEMORY(%)
vps-c67c8a44   125m         6%       1276Mi          33%

NAMESPACE     NAME                                      CPU(cores)   MEMORY(bytes)
kube-system   coredns-7cfb7bc9c7-6zlvb                  3m           12Mi
kube-system   local-path-provisioner-77b9867795-hchv5   1m           6Mi
kube-system   metrics-server-6dc596dfb8-drjwb           11m          18Mi
kube-system   svclb-traefik-bd3a316e-spx9h              0m           0Mi
kube-system   traefik-7c8544f77-5mc87                   2m           15Mi

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.2Gi       130Mi       2.1Mi       2.7Gi       2.5Gi
Swap:          2.0Gi          0B       2.0Gi
```

## Synthèse des mesures

| Mesure | Avant K3s | Après K3s (6 min) |
|---|---|---|
| RAM utilisée (`free`) | 569 Mio | 1,2 Gio |
| RAM disponible (`free`) | 3,2 Gio | 2,5 Gio |
| Mémoire du nœud (`kubectl top nodes`) | — | 1276 Mio (33 %) |
| CPU du nœud | — | 125m (6 % de 2 vCPU) |
| Swap utilisé | 0 B | 0 B |

Constats :
- Le nœud est `Ready`, les 5 pods système tournent sans redémarrage, les 2 jobs d'installation de Traefik sont terminés.
- Surcoût de K3s à vide : environ **+650 Mio**. Les pods système ne pèsent qu'environ **51 Mio** au total ;
  l'essentiel est consommé par le processus `k3s` lui-même (API server, scheduler, contrôleurs, kubelet,
  base SQLite intégrée) et par containerd.
- Marge pour l'application : environ **2,5 Gio** disponibles pour troc-service, notification-service,
  PostgreSQL, RabbitMQ, Keycloak, Prometheus et Grafana. Keycloak et les deux JVM Spring Boot seront les
  plus gourmands : à mesurer un par un avant de tout déployer.

**Fin de la partie 1 (installation de K3s). L'expérience 1 n'est pas terminée.**

## Parties restantes (source : `docs/cadrage.md` §3, point 1)

Cadre commun (`docs/cadrage.md` §3) : une journée maximum par expérience, au-delà on documente la difficulté
et on avance ; travail dans le namespace `sandbox`, supprimé après usage, jamais en même temps que `app` si la
RAM ne le permet pas. Chaque commande sur le VPS est annoncée et validée avant d'être lancée.

### Partie 2 — RAM réelle de chaque composant (faite, hors JVM Spring Boot ; cert-manager mesuré en partie 3)
- Mesurer la consommation réelle (`kubectl top pods`, `free -h`) de chaque composant de la stack, un par un.
- Point de départ : K3s à vide = 1,2 Gio utilisés, 2,5 Gio disponibles (partie 1).

#### Méthode
- Manifests : `experimentations/exp1/` (namespace `sandbox`), déployés de façon cumulative, un composant à la fois.
- Après chaque ajout : pod prêt, 2 min de stabilisation, puis `kubectl top pods`, `kubectl top nodes`, `free -h`.
- Seuil d'arrêt : RAM disponible < 500 Mio.
- Limites mémoire larges (1Gi) pour les composants non Java ; **Keycloak limité à 768Mi dès le départ**
  (la JVM dimensionne son tas d'après la limite du conteneur, une limite large fausserait la mesure).
- Secrets générés sur le VPS (`openssl rand -hex 24`), stockés en Secret Kubernetes, jamais affichés.

| Composant | Image | requests / limits mémoire |
|---|---|---|
| PostgreSQL | `postgres:18.6-alpine` | 128Mi / 1Gi |
| Keycloak (`start`) | `quay.io/keycloak/keycloak:26.8.1` | 768Mi / 768Mi |
| RabbitMQ | `rabbitmq:4.4.0-management-alpine` | 128Mi / 1Gi |
| Prometheus | `prom/prometheus:v3.15.1` | 128Mi / 1Gi |
| Grafana | `grafana/grafana:13.3.0` | 128Mi / 1Gi |

#### A — Transfert, namespace, secret PostgreSQL, validation
```powershell
scp -r experimentations/exp1 ubuntu@51.178.39.218:~/exp1
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/00-namespace.yaml && kubectl -n sandbox create secret generic postgres-secrets --from-literal=POSTGRES_PASSWORD=$(openssl rand -hex 24) --from-literal=KEYCLOAK_DB_PASSWORD=$(openssl rand -hex 24) && kubectl apply --dry-run=server -f ~/exp1/'
```
Résultat : `namespace/sandbox created`, `secret/postgres-secrets created`, les 19 objets des six manifests
acceptés en `server dry run`, aucune erreur.

#### B — PostgreSQL
```powershell
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/10-postgres.yaml && kubectl -n sandbox rollout status deploy/postgres --timeout=180s'
ssh ubuntu@51.178.39.218 'kubectl -n sandbox get pods && kubectl -n sandbox exec deploy/postgres -- psql -U postgres -l && sleep 120 && date -u && kubectl -n sandbox top pods && kubectl top nodes && free -h'
```
Résultat (13:25:43 UTC) :
```
postgres-5fcf45dbbb-9t5m5   1/1     Running   0          102s

 Name      |  Owner   | Encoding
 keycloak  | keycloak | UTF8        ← base dédiée créée par le script d'initialisation
 postgres  | postgres | UTF8

postgres-5fcf45dbbb-9t5m5   5m    29Mi

vps-c67c8a44   123m   6%   1212Mi   31%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.2Gi       220Mi        19Mi       2.6Gi       2.5Gi
Swap:          2.0Gi        12Ki       2.0Gi
```
Constats :
- PostgreSQL au repos, sans charge : **29 Mio**.
- Base `keycloak` (propriétaire `keycloak`, UTF8) bien créée.
- Difficulté : depuis PowerShell 5.1, les guillemets doubles à l'intérieur d'une commande `ssh '...'` sont
  supprimés à la transmission (`psql -c "\l"` est arrivé sous la forme `l`). Contournement : éviter les
  guillemets doubles dans les commandes distantes (ici `psql -l`).

#### C — Keycloak (mode `start`, limite 768Mi)
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox create secret generic keycloak-secrets --from-literal=KC_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 24) && kubectl apply -f ~/exp1/20-keycloak.yaml && for i in $(seq 1 24); do echo --- $(date -u +%T); kubectl -n sandbox get pod -l app=keycloak --no-headers; kubectl -n sandbox top pod -l app=keycloak --no-headers 2>/dev/null; sleep 15; done; kubectl -n sandbox rollout status deploy/keycloak --timeout=60s && kubectl -n sandbox logs deploy/keycloak --tail=15 && kubectl -n sandbox exec deploy/keycloak -- cat /sys/fs/cgroup/memory.peak && sleep 120 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox top pods && kubectl top nodes && free -h'
```

Suivi du démarrage (relevé toutes les 15 s, `kubectl top`) :

| Heure (UTC) | Âge | Prêt | CPU | RAM |
|---|---|---|---|---|
| 13:27:12 | 0 s | 0/1 (ContainerCreating) | — | — |
| 13:27:43 | 31 s | 0/1 | 988m | 260Mi |
| 13:27:58 | 47 s | 0/1 | 999m | 390Mi |
| 13:28:14 | 62 s | 0/1 | 1000m | 521Mi |
| 13:28:30 | 78 s | 0/1 | 966m | 122Mi |
| 13:28:45 | 94 s | 0/1 | 995m | 266Mi |
| 13:29:01 | 109 s | 0/1 | 990m | 520Mi |
| 13:29:32 | 140 s | 0/1 | 989m | 449Mi |
| 13:29:48 | 156 s | **1/1** | 911m | 500Mi |
| 13:30:19 | 187 s | 1/1 | 20m | 548Mi |
| 13:33:09 | 357 s | 1/1 | 8m | 551Mi |

Extrait des logs :
```
... ISPN000078: Starting JGroups channel `ISPN` with stack `jdbc-ping`
... KC-SERVICES0050: Initializing master realm
... KC-SERVICES0077: Created temporary admin user with username admin
... Bootstrap completed in 55.604000 seconds
```

Pic mémoire du conteneur (`/sys/fs/cgroup/memory.peak`) : `805306368` octets = **768 Mio, soit exactement la limite**.

Mesure au repos (13:35:25 UTC, 8 min après le déploiement) :
```
keycloak-57869d58f6-wfghj   1/1     Running   0          8m13s
postgres-5fcf45dbbb-9t5m5   1/1     Running   0          13m

keycloak-57869d58f6-wfghj   12m    552Mi
postgres-5fcf45dbbb-9t5m5   8m     57Mi

vps-c67c8a44   176m   8%   1979Mi   51%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.7Gi       209Mi        27Mi       2.1Gi       2.0Gi
Swap:          2.0Gi       780Ki       2.0Gi
```

Constats :
- Keycloak au repos : **552 Mio** (limite 768Mi). Aucun redémarrage, pas d'OOMKilled.
- PostgreSQL passe de 29 à **57 Mio** avec les connexions de Keycloak.
- Démarrage : prêt en **≈ 2 min 36 s**, bootstrap applicatif de 55,6 s. Le CPU reste collé à la limite
  (1 cœur) pendant tout le démarrage : le démarrage est freiné par la limite CPU.
- Deux phases visibles dans la RAM : montée à 521Mi puis chute à 122Mi vers 78 s. Interprétation : `start` sans
  image pré-construite lance d'abord une JVM de construction (`build` automatique), qui se termine, puis la JVM
  du serveur.
- **Le pic atteint exactement la limite de 768Mi.** Le conteneur a donc été sous pression mémoire au démarrage,
  sans être tué. `memory.peak` compte aussi le cache de fichiers (récupérable par le noyau) : ce chiffre seul ne
  dit pas si la mémoire de la JVM elle-même a frôlé la limite. À vérifier (`memory.events`).
- Piste (non appliquée) : construire une image Keycloak optimisée (`kc.sh build` à la construction de l'image,
  puis `start --optimized`) supprimerait la phase de construction au démarrage, donc une partie du pic et du temps.
- Total après PostgreSQL + Keycloak : 1,7 Gio utilisés, **2,0 Gio disponibles**.

#### D — Vérification de la pression mémoire de Keycloak
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox exec deploy/keycloak -- cat /sys/fs/cgroup/memory.events && kubectl -n sandbox exec deploy/keycloak -- grep -E ^anon\ \|^file\ \|^active_file\|^inactive_file /sys/fs/cgroup/memory.stat'
```
Résultat (≈ 10 min après le déploiement) :
```
low 0
high 0
max 0
oom 0
oom_kill 0
oom_group_kill 0
---
anon 550080512           (525 Mio : mémoire propre de la JVM)
file 227000320           (216 Mio : cache de fichiers)
inactive_file 195948544  (187 Mio : cache inactif, récupérable)
active_file 31051776     (30 Mio)
```
Constats :
- Aucun OOM, aucun processus tué, aucun événement `max` enregistré.
- Mémoire propre de la JVM au repos : **525 Mio** ; le reste de l'usage mesuré est du cache de fichiers,
  en grande partie inactif et récupérable par le noyau.
- Incohérence non expliquée : `memory.peak` = limite exacte, alors que `memory.events` ne compte aucun
  événement `max`. À garder en tête ; le compteur fiable pour le risque d'OOM est `memory.events`.
- Marge de la JVM avant la limite : ≈ 240 Mio au repos. Avec les réglages par défaut de Keycloak
  (tas maximal ≈ 70 % de la limite, soit ≈ 537 Mio), la mémoire peut encore monter sous charge (connexions,
  sessions). **Limite de 768Mi suffisante au repos, à revérifier sous charge** (partie 4 ou 5).

#### E — RabbitMQ
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox create secret generic rabbitmq-secrets --from-literal=RABBITMQ_DEFAULT_PASS=$(openssl rand -hex 24) && kubectl apply -f ~/exp1/30-rabbitmq.yaml && kubectl -n sandbox rollout status deploy/rabbitmq --timeout=300s && sleep 120 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox top pods && kubectl -n sandbox exec deploy/rabbitmq -- rabbitmq-diagnostics -q memory_breakdown --unit mb && kubectl -n sandbox exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events && kubectl top nodes && free -h'
```
Résultat (13:40:41 UTC) :
```
keycloak-57869d58f6-wfghj   1/1     Running   0               13m
postgres-5fcf45dbbb-9t5m5   1/1     Running   0               18m
rabbitmq-c4797d8bc-zl5vq    1/1     Running   1 (2m37s ago)   2m58s    ← 1 redémarrage

keycloak-57869d58f6-wfghj   11m    564Mi
postgres-5fcf45dbbb-9t5m5   8m     48Mi
rabbitmq-c4797d8bc-zl5vq    240m   92Mi

Répartition interne (memory_breakdown, principaux postes) :
reserved_unallocated: 52.9 mb (46.85%)
code:                 23.1 mb (20.47%)
other_system:         19.8 mb (17.5%)
other_proc:           15.1 mb (13.34%)
other_ets:             3.5 mb (3.13%)
(files, connexions, canaux : 0)

cgroup : memory.peak = 182726656 (174 Mio) ; low/high/max/oom/oom_kill = 0

vps-c67c8a44   316m   15%   1888Mi   49%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.8Gi       262Mi        44Mi       2.0Gi       1.9Gi
Swap:          2.0Gi       1.0Mi       2.0Gi
```
Constats provisoires :
- RabbitMQ au repos, sans file ni connexion : **92 Mio** (pic 174 Mio au démarrage).
- **Problème : le conteneur a redémarré une fois**, environ 20 s après sa création. Les compteurs cgroup
  ci-dessus sont ceux du conteneur relancé : ils ne disent rien sur la cause du premier arrêt. Cause à établir
  (`kubectl describe`, `kubectl logs --previous`) avant de continuer.
- CPU de RabbitMQ encore à 240m 2 min après le redémarrage (contre ~10m pour Keycloak au repos) : à surveiller.
- Total après PostgreSQL + Keycloak + RabbitMQ : 1,8 Gio utilisés, **1,9 Gio disponibles**.

#### F — Cause du redémarrage de RabbitMQ
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox describe pod -l app=rabbitmq | grep -A 8 -E Last\ State\|Events: && kubectl -n sandbox logs deploy/rabbitmq --previous --tail=40 && kubectl -n sandbox top pod -l app=rabbitmq'
```
Résultat (extraits) :
```
Last State:     Terminated
  Reason:       Error
  Exit Code:    1
  Started:      Wed, 28 Oct 2026 13:37:50 +0000
  Finished:     Wed, 28 Oct 2026 13:38:04 +0000

Warning  Unhealthy  3m56s  kubelet  Readiness probe failed:

[error] {failed_to_start_child,auth,
[error]  {"Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces", ...
Kernel pid terminated (application_controller) ("{application_start_failure,rabbitmq_prelaunch, ...

rabbitmq-c4797d8bc-zl5vq   127m   92Mi
```
Analyse :
- Pas un problème de mémoire : arrêt `Error`, code 1, 14 s après le démarrage.
- RabbitMQ n'a pas pu lire son cookie Erlang (`.erlang.cookie`, `eacces` = permission refusée).
- Explication la plus probable : le conteneur démarre en root, le script d'entrée de l'image donne
  `/var/lib/rabbitmq` à l'utilisateur `rabbitmq` puis lance le serveur sous cet utilisateur. La sonde de
  disponibilité (`rabbitmq-diagnostics`) s'exécute, elle, en **root** : lancée avant que le serveur ait créé
  son cookie, elle l'a créée elle-même, avec root pour propriétaire. Le serveur, sous `rabbitmq`, n'a plus pu le lire.
  Au redémarrage, le script d'entrée a redonné le fichier à `rabbitmq` : le second démarrage a réussi.
- Le CPU redescend (240m → 127m) : fin de démarrage, à revérifier au repos.

#### G — Correction : RabbitMQ sous l'utilisateur rabbitmq
Vérification de l'identifiant dans l'image :
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox exec deploy/rabbitmq -- id rabbitmq && kubectl -n sandbox exec deploy/rabbitmq -- ls -ln /var/lib/rabbitmq'
```
```
uid=100(rabbitmq) gid=101(rabbitmq) groups=101(rabbitmq),101(rabbitmq)
```
Modification de `experimentations/exp1/30-rabbitmq.yaml` : `securityContext` de pod `runAsNonRoot: true`,
`runAsUser: 100`, `runAsGroup: 101`, `fsGroup: 101`, et `allowPrivilegeEscalation: false` sur le conteneur.
La sonde tourne ainsi sous le même utilisateur que le serveur.

#### H — Application de la correction et nouvelle mesure
```powershell
scp experimentations/exp1/30-rabbitmq.yaml ubuntu@51.178.39.218:~/exp1/30-rabbitmq.yaml
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/30-rabbitmq.yaml && kubectl -n sandbox rollout status deploy/rabbitmq --timeout=300s && sleep 120 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox exec deploy/rabbitmq -- id && kubectl -n sandbox exec deploy/rabbitmq -- ls -lna /var/lib/rabbitmq && kubectl -n sandbox top pods && kubectl -n sandbox exec deploy/rabbitmq -- cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events && kubectl top nodes && free -h'
```
Résultat (13:48:19 UTC) :
```
keycloak-57869d58f6-wfghj   1/1     Running   0          21m
postgres-5fcf45dbbb-9t5m5   1/1     Running   0          26m
rabbitmq-5488f6cb6c-b4s8k   1/1     Running   0          2m38s     ← aucun redémarrage

uid=100(rabbitmq) gid=101(rabbitmq) groups=101(rabbitmq)
-r--------    1 100      101             20 Oct 28 00:00 .erlang.cookie
drwxr-sr-x    4 100      101           4096 Oct 28 13:46 mnesia

keycloak-57869d58f6-wfghj   41m    570Mi
postgres-5fcf45dbbb-9t5m5   6m     48Mi
rabbitmq-5488f6cb6c-b4s8k   97m    144Mi

cgroup RabbitMQ : memory.peak = 174178304 (166 Mio) ; low/high/max/oom/oom_kill = 0

vps-c67c8a44   423m   21%   1921Mi   50%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.9Gi       336Mi        44Mi       1.9Gi       1.9Gi
Swap:          2.0Gi       1.0Mi       2.0Gi
```
Constats :
- **Correction validée** : nouveau pod sur volume vide, 0 redémarrage ; processus sous uid 100, cookie
  appartenant à `rabbitmq` (lecture seule pour lui). RabbitMQ ne tourne plus en root.
- RAM de RabbitMQ au repos : **144 Mio** cette fois (92 Mio lors de la mesure E, au même âge du pod) ;
  pic de démarrage 166 Mio. L'écart entre les deux mesures sera tranché par la mesure finale de la stack complète.
- CPU de RabbitMQ toujours autour de 100m sans aucune connexion. Hypothèse : l'attente active des ordonnanceurs
  Erlang (réglable avec `+sbwt none`) ; à étudier dans l'expérience 2.
- Total après PostgreSQL + Keycloak + RabbitMQ : 1,9 Gio utilisés, **1,9 Gio disponibles**.

#### I — Prometheus
Première tentative bloquée par le mode automatique de Claude Code (application de droits à l'échelle du
cluster : `ClusterRole` / `ClusterRoleBinding`) ; relancée après autorisation explicite.
```powershell
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/40-prometheus.yaml && kubectl -n sandbox rollout status deploy/prometheus --timeout=180s && sleep 120 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox exec deploy/prometheus -- wget -qO- ''http://localhost:9090/api/v1/query?query=up'' && kubectl -n sandbox exec deploy/prometheus -- wget -qO- ''http://localhost:9090/api/v1/query?query=prometheus_tsdb_head_series'' && kubectl -n sandbox top pods && kubectl -n sandbox exec deploy/prometheus -- cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events && kubectl top nodes && free -h'
```
Résultat (13:52:59 UTC) :
```
keycloak-57869d58f6-wfghj    1/1     Running   0          25m
postgres-5fcf45dbbb-9t5m5    1/1     Running   0          30m
prometheus-c9b7f656d-l9hmv   1/1     Running   0          2m10s
rabbitmq-5488f6cb6c-b4s8k    1/1     Running   0          7m18s

Cibles (up) : keycloak = 1, prometheus = 1, rabbitmq = 1, kubernetes-cadvisor = 1
Séries en mémoire (prometheus_tsdb_head_series) : 4910

keycloak-57869d58f6-wfghj    6m     573Mi
postgres-5fcf45dbbb-9t5m5    5m     48Mi
prometheus-c9b7f656d-l9hmv   6m     37Mi
rabbitmq-5488f6cb6c-b4s8k    109m   90Mi

cgroup Prometheus : memory.peak = 44040192 (42 Mio) ; low/high/max/oom/oom_kill = 0

vps-c67c8a44   265m   13%   1967Mi   51%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       1.9Gi       141Mi        46Mi       2.0Gi       1.8Gi
Swap:          2.0Gi       1.0Mi       2.0Gi
```
Constats :
- Les 4 cibles sont collectées (droits RBAC et adresses corrects).
- Prometheus : **37 Mio** pour 4 910 séries, 2 min après le démarrage. Sa RAM augmente avec le temps (le bloc
  en mémoire couvre jusqu'à 2 h de données) : la mesure finale, plus tardive, sera plus représentative.
- RabbitMQ redescendu à **90 Mio** (144 Mio à la mesure H) : la valeur de 144 Mio était transitoire.
- Nettoyage à prévoir en fin d'expérience : `ClusterRole` et `ClusterRoleBinding` `sandbox-prometheus` ne
  sont pas dans le namespace, la suppression de `sandbox` ne les supprime pas.
- Total : 1,9 Gio utilisés, **1,8 Gio disponibles**.

#### J — Grafana et mesure de la stack complète
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox create secret generic grafana-secrets --from-literal=GF_SECURITY_ADMIN_PASSWORD=$(openssl rand -hex 24) && kubectl apply -f ~/exp1/50-grafana.yaml && kubectl -n sandbox rollout status deploy/grafana --timeout=180s && kubectl -n sandbox exec deploy/prometheus -- wget -qO- http://grafana.sandbox.svc:3000/api/health && sleep 300 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox top pods && for d in postgres keycloak rabbitmq prometheus grafana; do echo $d $(kubectl -n sandbox exec deploy/$d -- grep oom_kill\  /sys/fs/cgroup/memory.events); done && kubectl -n sandbox exec deploy/prometheus -- wget -qO- ''http://localhost:9090/api/v1/query?query=prometheus_tsdb_head_series'' && kubectl top nodes && free -h'
```
Résultat (14:01:13 UTC) :
```
Santé Grafana (depuis le pod Prometheus) : "database": "ok", "version": "13.3.0"

grafana-5f94899bc5-gw2vt     1/1     Running   0          6m3s
keycloak-57869d58f6-wfghj    1/1     Running   0          34m
postgres-5fcf45dbbb-9t5m5    1/1     Running   0          39m
prometheus-c9b7f656d-l9hmv   1/1     Running   0          10m
rabbitmq-5488f6cb6c-b4s8k    1/1     Running   0          15m

grafana-5f94899bc5-gw2vt     19m    532Mi
keycloak-57869d58f6-wfghj    8m     544Mi
postgres-5fcf45dbbb-9t5m5    3m     47Mi
prometheus-c9b7f656d-l9hmv   10m    39Mi
rabbitmq-5488f6cb6c-b4s8k    158m   83Mi

oom_kill : postgres 0, keycloak 0, rabbitmq 0, prometheus 0, grafana 0
Séries Prometheus : 5108

vps-c67c8a44   322m   16%   2281Mi   59%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       2.2Gi       146Mi        49Mi       1.8Gi       1.6Gi
Swap:          2.0Gi        23Mi       2.0Gi
```
Constats provisoires :
- Les cinq composants tournent, aucun redémarrage, aucun OOM. Grafana répond et joint sa base interne.
- **Anomalie : Grafana à 532 Mio**, presque autant que Keycloak, pour une instance vide. Valeur inhabituelle
  pour Grafana : `kubectl top` compte aussi le cache de fichiers actif. Hypothèse : Grafana 13 télécharge des
  plugins au premier démarrage dans `/var/lib/grafana` (emptyDir), ce qui remplit le cache de fichiers du conteneur.
  À vérifier avec `memory.stat` (mémoire propre / cache) avant de retenir ce chiffre.
- Premier usage du swap : 23 Mio.
- Total de la stack (hors JVM Spring Boot) : 2,2 Gio utilisés, **1,6 Gio disponibles** (seuil d'arrêt : 500 Mio).

#### K — Analyse de la mémoire de Grafana
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox exec deploy/grafana -- grep -E ^anon\ \|^file\ \|^active_file\|^inactive_file\|^shmem\  /sys/fs/cgroup/memory.stat && kubectl -n sandbox exec deploy/grafana -- cat /sys/fs/cgroup/memory.peak && kubectl -n sandbox exec deploy/grafana -- du -sh /var/lib/grafana/plugins /var/lib/grafana/grafana.db && kubectl -n sandbox logs deploy/grafana | grep -i -E plugin.*install\|preinstall | tail -n 10'
```
Résultat :
```
anon 225189888            (215 Mio : mémoire propre)
file 757014528            (722 Mio : cache de fichiers)
shmem 0
inactive_file 448548864   (428 Mio)
active_file 308465664     (294 Mio)
memory.peak 1075150848    (≈ 1 Gio : la limite large du conteneur)

433.0M  /var/lib/grafana/plugins
1.6M    /var/lib/grafana/grafana.db

logger=plugin.installer ... msg="Installing plugin" pluginId=grafana-metricsdrilldown-app
logger=plugin.installer ... msg="Updating plugin" pluginId=grafana-pyroscope-datasource from=13.0.8 to=13.0.9
logger=plugin.installer ... msg="Updating plugin" pluginId=zipkin from=12.4.11 to=12.4.12
...
```
Analyse :
- **Hypothèse confirmée** : au démarrage, Grafana télécharge et met à jour ses plugins préinstallés
  (**433 Mo** sur disque). Ces écritures remplissent le cache de fichiers : 722 Mio, dont 294 Mio comptés comme
  « actifs » par `kubectl top`. Le pic a atteint la limite de 1 Gio (cache récupéré par le noyau, pas d'OOM).
- Mémoire propre de Grafana : **215 Mio**, non négligeable non plus : une partie vient probablement des
  processus des plugins côté serveur (pyroscope, etc.).
- Autres problèmes de ce comportement : téléchargement depuis Internet **à chaque démarrage** (emptyDir),
  et versions de plugins qui changent toutes seules (`13.0.8 → 13.0.9`) : la stack n'est plus reproductible.
- Correction : `GF_PLUGINS_PREINSTALL_DISABLED=true` dans `experimentations/exp1/50-grafana.yaml`. Conséquence :
  applications Drilldown, Pyroscope et Zipkin absentes, inutiles pour le POC.

#### L — Correction de Grafana et mesure finale de la stack
```powershell
scp experimentations/exp1/50-grafana.yaml ubuntu@51.178.39.218:~/exp1/50-grafana.yaml
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/50-grafana.yaml && kubectl -n sandbox rollout status deploy/grafana --timeout=180s && kubectl -n sandbox exec deploy/prometheus -- wget -qO- http://grafana.sandbox.svc:3000/api/health && sleep 300 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox exec deploy/grafana -- du -sh /var/lib/grafana/plugins && kubectl -n sandbox logs deploy/grafana | grep -c -i plugin.*install && kubectl -n sandbox top pods && for d in postgres keycloak rabbitmq prometheus grafana; do echo $d $(kubectl -n sandbox exec deploy/$d -- grep -E ^anon\  /sys/fs/cgroup/memory.stat) $(kubectl -n sandbox exec deploy/$d -- grep oom_kill\  /sys/fs/cgroup/memory.events); done && kubectl top nodes && free -h'
```
Résultat (14:13:21 UTC) :
```
Santé Grafana : "database": "ok", "version": "13.3.0"

grafana-79d865bd7d-d5wkv     1/1     Running   0          5m32s
keycloak-57869d58f6-wfghj    1/1     Running   0          46m
postgres-5fcf45dbbb-9t5m5    1/1     Running   0          51m
prometheus-c9b7f656d-l9hmv   1/1     Running   0          22m
rabbitmq-5488f6cb6c-b4s8k    1/1     Running   0          27m

4.0K    /var/lib/grafana/plugins
1       (lignes de log contenant « plugin…install »)

grafana-79d865bd7d-d5wkv     17m    232Mi
keycloak-57869d58f6-wfghj    6m     549Mi
postgres-5fcf45dbbb-9t5m5    7m     45Mi
prometheus-c9b7f656d-l9hmv   12m    40Mi
rabbitmq-5488f6cb6c-b4s8k    132m   83Mi

postgres   anon 13516800   oom_kill 0
keycloak   anon 559411200  oom_kill 0
rabbitmq   anon 65953792   oom_kill 0
prometheus anon 40108032   oom_kill 0
grafana    anon 217886720  oom_kill 0

vps-c67c8a44   322m   16%   2074Mi   54%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       2.1Gi       548Mi        44Mi       1.4Gi       1.6Gi
Swap:          2.0Gi        85Mi       1.9Gi
```
Constats :
- Correction validée : dossier des plugins vide (4 Ko), Grafana passe de 532 à **232 Mio** (`kubectl top`).
  Une ligne de log correspond encore au motif « plugin…install » ; vu le dossier vide, ce n'est pas une
  installation (ligne non affichée, à regarder si besoin).
- Mémoire propre de Grafana inchangée (≈ 208 Mio) : c'est le coût réel de Grafana 13, plugins ou non.

#### Bilan de la partie 2 (stack au repos, sans charge)

| Composant | Limite mémoire | `kubectl top` | Mémoire propre (`anon`) | Pic au démarrage |
|---|---|---|---|---|
| Keycloak 26.8.1 (`start`) | 768Mi | 549 Mio | 534 Mio | 768 Mio (= limite, sans OOM) |
| Grafana 13.3.0 (sans plugins préinstallés) | 1Gi | 232 Mio | 208 Mio | — |
| RabbitMQ 4.4.0 | 1Gi | 83 Mio | 63 Mio | 166 Mio |
| PostgreSQL 18.6 (bases postgres + keycloak) | 1Gi | 45 Mio | 13 Mio | — |
| Prometheus 3.15.1 (≈ 5 100 séries) | 1Gi | 40 Mio | 38 Mio | 42 Mio |
| **Total des 5 composants** | | **949 Mio** | **856 Mio** | |

| Étape | RAM utilisée (`free`) | RAM disponible | Swap |
|---|---|---|---|
| Système seul | 569 Mio | 3,2 Gio | 0 |
| + K3s | 1,2 Gio | 2,5 Gio | 0 |
| + stack complète | 2,1 Gio | **1,6 Gio** | 85 Mio |

Constats :
- Aucun OOM, aucun redémarrage sur l'ensemble de la stack (hors le redémarrage de RabbitMQ, corrigé en G/H).
- Keycloak représente **plus de la moitié** de la RAM de la stack. Grafana arrive en deuxième.
- L'écart entre `kubectl top` et `anon` pour PostgreSQL (45 contre 13 Mio) vient de sa mémoire partagée
  (`shared_buffers`), comptée hors `anon`.
- Marge restante : ≈ 1,6 Gio pour troc-service, notification-service (2 JVM Spring Boot) et cert-manager
  (partie 3). Avec une limite de 384 à 512 Mio par JVM, la marge restante serait d'environ 600 Mio à 1 Gio :
  faisable, mais **le HPA à 3 replicas (partie 5) ne tiendra pas pour les deux services en même temps**.
- Le swap commence à être utilisé (23 → 85 Mio) : pages peu utilisées déplacées par le noyau, à surveiller.
- Non mesuré dans cette partie : les JVM Spring Boot (services pas encore écrits) et cert-manager (partie 3).
- Les vraies requests/limits seront fixées à partir de ces chiffres, avec une marge, au moment du déploiement `app`.

### Partie 3 — Traefik + cert-manager + Let's Encrypt (faite)
- Traefik est déjà installé par K3s (partie 1) ; installer cert-manager.
- Émettre un certificat avec Let's Encrypt **staging** d'abord, puis **production**, sur les deux sous-domaines
  `epouvante.menuly-app.com` et `auth.epouvante.menuly-app.com`.

#### Méthode et choix (validés avant la première commande)
- Manifests : `experimentations/exp1/60-cert-manager.yaml`, `61-cluster-issuers.yaml`, `62-whoami.yaml` (commit `109a862`).
- **Installation par le `HelmChart` de K3s** (contrôleur Helm intégré, pas de Helm sur le VPS) : version figée,
  requests/limits fixées par les valeurs du chart, ce que le manifest statique officiel ne permet pas.
  Limites larges (32Mi / 256Mi) pour mesurer avant de fixer les vraies valeurs.
- **Validation HTTP-01 par `Ingress` standard** (`ingressClassName: traefik`) plutôt que par `IngressRoute` Traefik :
  cert-manager crée le `Certificate` à partir d'une simple annotation, et le format reste portable.
- **Pas d'e-mail dans les `ClusterIssuer`** : Let's Encrypt n'envoie plus d'alertes d'expiration, et le dépôt public
  ne contient aucune donnée personnelle.
- **Application de test `traefik/whoami`** derrière les deux hôtes, sans Keycloak : exposer Keycloak relève de la
  partie 4 (hostname, console d'administration non publique, `docs/cadrage.md` §1.4.14).
- Staging d'abord, à cause des quotas de production (5 échecs de validation par nom et par heure, 5 certificats
  identiques par semaine).

#### Étape 0 — Vérifications préalables (poste local, rien sur le VPS)
```bash
nslookup -type=A epouvante.menuly-app.com 1.1.1.1        # idem AAAA, et pour auth.epouvante.menuly-app.com
curl -s -H 'accept: application/dns-json' 'https://cloudflare-dns.com/dns-query?name=menuly-app.com&type=CAA'
curl -sS -o /dev/null -w '%{http_code}\n' http://epouvante.menuly-app.com
curl -s https://api.github.com/repos/cert-manager/cert-manager/releases/latest | grep -E '"(tag_name|published_at|prerelease)"'
curl -s https://charts.jetstack.io/index.yaml | grep -c 'cert-manager-v1.21.3.tgz'
```
Résultat :

| Vérification | Résultat |
|---|---|
| A (`epouvante.`, `auth.`) | `51.178.39.218` |
| AAAA (les deux noms et `menuly-app.com`) | aucun |
| CAA (les deux noms et `menuly-app.com`) | aucun (toutes les autorités autorisées) |
| HTTP port 80 | `404 page not found` (réponse de Traefik) sur les deux noms |
| cert-manager | `v1.21.3`, publiée le 2026-10-23, chart présent sur `charts.jetstack.io` |
| whoami | `v1.12.0`, publiée le 2026-07-30 |

Constats :
- Rien ne bloque l'émission : pas d'IPv6 (K3s est en IPv4 seul, un AAAA aurait fait échouer la validation), pas de CAA.
- Difficulté : le `nslookup` de Windows ne connaît pas le type CAA (`unknown query type: CAA`). Contournement :
  requête DNS over HTTPS (DoH) chez Cloudflare.
- cert-manager v1.21.3 n'avait que 5 jours : version de correctifs de la branche 1.21, conservée.

#### Étape 1 — État de départ
```powershell
ssh ubuntu@51.178.39.218 'date -u && kubectl get pods -A && kubectl top nodes && kubectl top pods -A && free -h'
```
Résultat (14:28:53 UTC) : stack de la partie 2 toujours en place dans `sandbox` (5 pods, 0 redémarrage).
```
vps-c67c8a44   312m   15%   2142Mi   56%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       2.1Gi       553Mi        44Mi       1.5Gi       1.7Gi
Swap:          2.0Gi        83Mi       1.9Gi
```

#### Étape 2 — Installation de cert-manager
```powershell
scp experimentations/exp1/60-cert-manager.yaml experimentations/exp1/61-cluster-issuers.yaml experimentations/exp1/62-whoami.yaml ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/60-cert-manager.yaml && for i in $(seq 1 30); do kubectl -n kube-system get job helm-install-cert-manager >/dev/null 2>&1 && break; sleep 2; done && kubectl -n kube-system wait --for=condition=complete job/helm-install-cert-manager --timeout=300s && for d in cert-manager cert-manager-webhook cert-manager-cainjector; do kubectl -n cert-manager rollout status deploy/$d --timeout=180s; done && kubectl -n cert-manager get pods,jobs && kubectl get crd | grep cert-manager.io && kubectl apply --dry-run=server -f ~/exp1/61-cluster-issuers.yaml'
```
Résultat (14:30 UTC) :
```
helmchart.helm.cattle.io/cert-manager created
job.batch/helm-install-cert-manager condition met
pod/cert-manager-6947f7d59c-hqwdj              1/1     Running   0          25s
pod/cert-manager-cainjector-6c448b4fd6-xngvv   1/1     Running   0          25s
pod/cert-manager-webhook-5fc8cfd99-qfsvf       1/1     Running   0          25s
certificaterequests / certificates / challenges / clusterissuers / issuers / orders  (6 CRD)
clusterissuer.cert-manager.io/letsencrypt-staging created (server dry run)
clusterissuer.cert-manager.io/letsencrypt-prod created (server dry run)
```
Constats :
- Installation en 29 s (job Helm), 3 pods prêts en 25 s, aucun redémarrage.
- Le webhook répond : les `ClusterIssuer` passent la validation côté serveur.
- L'installation crée des CRD et des ClusterRoles : autorisation explicite donnée avant la commande.

#### Étape 3 — RAM de cert-manager au repos
```powershell
ssh ubuntu@51.178.39.218 'sleep 120 && date -u && kubectl -n cert-manager get pods && kubectl -n kube-system get jobs && kubectl -n cert-manager get events --field-selector reason=Completed,involvedObject.kind=Job 2>/dev/null | tail -n 3; kubectl -n cert-manager top pods && for d in cert-manager cert-manager-webhook cert-manager-cainjector; do echo $d $(kubectl -n cert-manager get deploy/$d -o jsonpath={.spec.template.spec.containers[0].resources}) $(kubectl -n cert-manager exec deploy/$d -- cat /sys/fs/cgroup/memory.stat 2>/dev/null | grep -E ^anon\ ) $(kubectl -n cert-manager exec deploy/$d -- cat /sys/fs/cgroup/memory.events 2>/dev/null | grep oom_kill\ ); done; kubectl top nodes && free -h'
```
Résultat (14:41:15 UTC, pods âgés de 10 min) :
```
10m   Normal   Completed   job/cert-manager-startupapicheck   Job completed

cert-manager-6947f7d59c-hqwdj              5m   21Mi
cert-manager-cainjector-6c448b4fd6-xngvv   4m   17Mi
cert-manager-webhook-5fc8cfd99-qfsvf       2m   14Mi

resources (les 3) : {"limits":{"cpu":"200m","memory":"256Mi"},"requests":{"cpu":"10m","memory":"32Mi"}}

vps-c67c8a44   356m   17%   2295Mi   60%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       2.2Gi       216Mi        45Mi       1.6Gi       1.5Gi
Swap:          2.0Gi        83Mi       1.9Gi
```
Constats :
- cert-manager au repos : **52 Mio** pour les 3 pods. Les requests/limits du chart sont bien appliquées.
- Le job `startupapicheck` s'est terminé puis a été supprimé par le chart (comportement par défaut).
- Limite de la mesure : images distroless sans `cat`, la lecture du cgroup (`anon`, `oom_kill`) ne renvoie rien.
  Seul `kubectl top` est disponible (lecture possible depuis l'hôte, non faite).
- Le nœud prend +153 Mio (2142 → 2295 Mio) pour 52 Mio de pods : variations des autres composants et processus
  `containerd-shim` des nouveaux pods, du même ordre que les écarts vus en partie 2.

#### Étape 4 — Certificats staging
```powershell
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/61-cluster-issuers.yaml -f ~/exp1/62-whoami.yaml && kubectl -n sandbox rollout status deploy/whoami --timeout=120s && for i in $(seq 1 30); do [ $(kubectl -n sandbox get certificate --no-headers 2>/dev/null | wc -l) -ge 2 ] && break; sleep 2; done; date -u && kubectl -n sandbox get challenges; kubectl -n sandbox wait --for=condition=Ready certificate --all --timeout=300s; date -u && kubectl get clusterissuer && kubectl -n sandbox get certificate,certificaterequest,order,challenge && kubectl -n sandbox describe certificate | grep -A 12 -E ^Events: && kubectl -n sandbox get pods'
ssh ubuntu@51.178.39.218 'kubectl -n sandbox get pods; kubectl -n sandbox get challenges; kubectl -n sandbox get events --sort-by=.lastTimestamp | grep -i -E certificate\|challenge\|order\|acmesolver | tail -n 25'
```
Résultat (14:42:25 → 14:43:04 UTC) :
```
letsencrypt-prod      True    39s
letsencrypt-staging   True    39s
certificate.cert-manager.io/auth-epouvante-tls   True    auth-epouvante-tls   39s
certificate.cert-manager.io/epouvante-tls        True    epouvante-tls        39s
order.acme.cert-manager.io/auth-epouvante-tls-1-1630955684   valid
order.acme.cert-manager.io/epouvante-tls-1-1253835156        valid

Événements (extraits, chronologiques) :
IssuerNotReady       certificaterequest/auth-epouvante-tls-1   Referenced issuer does not have a Ready status condition
WaitingForApproval   certificaterequest/epouvante-tls-1        Not signing CertificateRequest until it is Approved
Presented            challenge/...                             Presented challenge using HTTP-01 challenge mechanism
Pulled               pod/cm-acme-http-solver-hq5s7             ... cert-manager-acmesolver:v1.21.3 in 1.775s. Image size: 6663508 bytes.
DomainVerified       challenge/...                             Domain "epouvante.menuly-app.com" verified with "HTTP-01" validation
DomainVerified       challenge/...                             Domain "auth.epouvante.menuly-app.com" verified with "HTTP-01" validation
Killing              pod/cm-acme-http-solver-*                 Stopping container acmesolver
CertificateIssued    certificaterequest/*-tls-1                Certificate fetched from issuer successfully
```
Vérification depuis le poste (bash) :
```bash
echo | openssl s_client -connect epouvante.menuly-app.com:443 -servername epouvante.menuly-app.com 2>/dev/null | openssl x509 -noout -issuer -subject -dates -ext subjectAltName
curl -sS https://epouvante.menuly-app.com
```
```
issuer=C=US, O=Let's Encrypt, CN=(STAGING) Ersatz Emmer YR2
subject=CN=epouvante.menuly-app.com
notBefore=Oct 28 13:44:30 2026 GMT
notAfter=Dec 31 13:44:29 2026 GMT
X509v3 Subject Alternative Name: DNS:epouvante.menuly-app.com
curl: (60) schannel: SEC_E_UNTRUSTED_ROOT (0x80090325)
```
(Identique pour `auth.epouvante.menuly-app.com`.)

Constats :
- Deux certificats staging émis en **≈ 40 s** après l'`apply` ; validation HTTP-01 en **22 s** (de `Presented` à
  `DomainVerified`). Pods `acmesolver` (image de 6,6 Mo) supprimés après validation, aucun `challenge` restant.
- `IssuerNotReady` et `WaitingForApproval` sont transitoires : les certificats sont demandés pendant
  l'enregistrement des comptes ACME, cert-manager réessaie seul.
- Le refus du certificat par `curl` est attendu : la racine staging n'est pas reconnue.
- Certificats staging de **64 jours** ; `notBefore` antidaté d'environ 1 h (pratique de Let's Encrypt pour tolérer
  les horloges en retard).
- Difficulté : le `grep -A 12 -E ^Events:` n'a rien trouvé (code 1), ce qui a interrompu la fin de la commande
  enchaînée par `&&`. Pods et événements relus par une seconde commande, rien de perdu.

#### Étape 5 — Passage en production
Changement d'émetteur directement sur les Ingress (le manifest garde le staging comme point de départ) :
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox annotate ingress whoami-epouvante whoami-auth cert-manager.io/cluster-issuer=letsencrypt-prod --overwrite && for i in $(seq 1 30); do kubectl -n sandbox get certificaterequest epouvante-tls-2 auth-epouvante-tls-2 >/dev/null 2>&1 && break; sleep 2; done; kubectl -n sandbox wait --for=condition=Ready certificaterequest/epouvante-tls-2 certificaterequest/auth-epouvante-tls-2 --timeout=300s; kubectl -n sandbox wait --for=condition=Ready certificate --all --timeout=60s; date -u; kubectl -n sandbox get certificate -o custom-columns=NOM:.metadata.name,EMETTEUR:.spec.issuerRef.name,PRET:.status.conditions[0].status,RENOUVELLEMENT:.status.renewalTime; kubectl -n sandbox get certificaterequest,order,challenge; kubectl -n sandbox get pods'
```
On attend les **nouvelles** demandes (`-2`) : les certificats staging étant déjà `Ready`, attendre les `Certificate`
seuls se serait terminé immédiatement.

Résultat (14:44:45 UTC) :
```
NOM                  EMETTEUR           PRET   RENOUVELLEMENT
auth-epouvante-tls   letsencrypt-prod   True   2026-12-27T13:46:10Z
epouvante-tls        letsencrypt-prod   True   2026-12-27T13:46:12Z
certificaterequest.cert-manager.io/auth-epouvante-tls-2   True   True   letsencrypt-prod   29s
certificaterequest.cert-manager.io/epouvante-tls-2        True   True   letsencrypt-prod   25s
order.acme.cert-manager.io/auth-epouvante-tls-2-1312749684   valid   29s
order.acme.cert-manager.io/epouvante-tls-2-3013016324        valid   25s
```
Vérification depuis le poste (mêmes commandes qu'à l'étape 4) :
```
issuer=C=US, O=Let's Encrypt, CN=YR1
subject=CN=epouvante.menuly-app.com
notBefore=Oct 28 13:46:13 2026 GMT
notAfter=Jan 26 13:46:12 2027 GMT
X509v3 Subject Alternative Name: DNS:epouvante.menuly-app.com

Réponse de whoami (curl sans -k) :
Host: epouvante.menuly-app.com
X-Forwarded-For: 10.42.0.1
X-Forwarded-Port: 443
X-Forwarded-Proto: https
X-Forwarded-Server: traefik-7c8544f77-5mc87
X-Real-Ip: 10.42.0.1
```
(Identique pour `auth.epouvante.menuly-app.com`.)

Constats :
- **Les deux sous-domaines sont servis en HTTPS avec un certificat de production reconnu**, sans aucun échec de
  validation. Réémission en **moins de 30 s** après le changement d'annotation.
- Certificats de production de **90 jours** (les 64 jours ne concernent que le staging) ; renouvellement prévu aux
  2/3 de la durée, soit 30 jours avant l'expiration.
- **IP réelle du client perdue** : whoami reçoit `X-Forwarded-For: 10.42.0.1` (adresse interne du cluster), pas
  l'adresse publique du poste. Le répartiteur intégré à K3s (ServiceLB) traduit l'adresse source avant Traefik.
  Conséquence : un rate limiting par IP sur `POST /annonces` (`docs/cadrage.md` §1.4.14) traiterait tous les clients
  comme un seul. Non bloquant pour la partie 3.

#### Étape 6 — Vérifications finales
```powershell
ssh ubuntu@51.178.39.218 'date -u; kubectl -n sandbox describe certificate epouvante-tls | grep -E Not\ After\|Not\ Before\|Renewal\ Time; kubectl -n sandbox get pods,challenges; kubectl -n sandbox get secret epouvante-tls auth-epouvante-tls; kubectl -n cert-manager get secret; kubectl -n cert-manager top pods; kubectl top nodes; free -h'
```
```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://epouvante.menuly-app.com
```
Résultat (14:45:51 UTC) :
```
Not After:     2027-01-26T13:46:12Z
Not Before:    2026-10-28T13:46:13Z
Renewal Time:  2026-12-27T13:46:12Z

6 pods Running dans sandbox (stack de la partie 2 + whoami), 0 redémarrage ; aucun challenge

Secrets (noms seulement) :
sandbox       epouvante-tls, auth-epouvante-tls                     kubernetes.io/tls
cert-manager  letsencrypt-prod-account, letsencrypt-staging-account Opaque (clés des comptes ACME)
cert-manager  cert-manager-webhook-ca, sh.helm.release.v1.cert-manager.v1

cert-manager-6947f7d59c-hqwdj              2m   42Mi
cert-manager-cainjector-6c448b4fd6-xngvv   2m   17Mi
cert-manager-webhook-5fc8cfd99-qfsvf       1m   17Mi

vps-c67c8a44   221m   11%   2340Mi   61%

               total        used        free      shared  buff/cache   available
Mem:           3.7Gi       2.3Gi       146Mi        45Mi       1.6Gi       1.4Gi
Swap:          2.0Gi        83Mi       1.9Gi

HTTP en clair : 200
```
Constats :
- Renouvellement planifié ; aucun résidu de validation.
- Les clés des comptes ACME sont dans `cert-manager` : elles survivent à la suppression de `sandbox`.
- Le contrôleur double (21 → 42 Mio) après les émissions : il garde en cache les certificats, Secrets et commandes
  qu'il surveille.
- HTTP en clair toujours servi (`200`), sans redirection vers HTTPS : à configurer pour `app`.

#### Bilan de la partie 3

| Pod cert-manager | Repos, avant émission | Après 4 émissions | Limite pendant le test |
|---|---|---|---|
| contrôleur | 21 Mio | 42 Mio | 256Mi |
| cainjector | 17 Mio | 17 Mio | 256Mi |
| webhook | 14 Mio | 17 Mio | 256Mi |
| **Total** | **52 Mio** | **76 Mio** | |

| Mesure | Valeur |
|---|---|
| Installation de cert-manager (job Helm) | 29 s |
| Émission staging (2 certificats, depuis l'`apply`) | ≈ 40 s, dont 22 s de validation HTTP-01 |
| Réémission production (2 certificats) | < 30 s |
| Durée des certificats | staging 64 j, production 90 j |
| RAM disponible (`free`) | 1,7 Gio avant → 1,4 Gio après |

Constats :
- **Objectif atteint** : HTTPS de production sur les deux sous-domaines, émission et renouvellement automatiques.
- cert-manager est léger (76 Mio après émissions) : il tient largement dans le budget RAM (`docs/cadrage.md` §1.4.18).
- Conservation de cert-manager et des `ClusterIssuer` pour `app` : décision consignée dans
  `docs/adr/0001-conserver-cert-manager.md`, avec les limites retenues.
- Difficultés : CAA invisible avec `nslookup` sous Windows (contourné par DoH) ; `grep` sans résultat qui interrompt
  une commande enchaînée par `&&`.
- Limites : pas de mesure `anon`/`oom_kill` (images distroless) ; renouvellement non testé (pas de réémission forcée
  en production, à cause des quotas) ; HTTP-01 seulement (pas de certificat wildcard, qui exigerait DNS-01).

### Partie 4 — Keycloak
- Déployer Keycloak et vérifier la séparation **issuer public** (URL HTTPS vue par le navigateur) / **JWKS interne**
  (clés récupérées par les services à l'intérieur du cluster).
- `auth.epouvante.menuly-app.com` pointe provisoirement vers whoami (partie 3) : rediriger l'Ingress `whoami-auth`
  vers Keycloak en gardant le Secret `auth-epouvante-tls` et l'émetteur `letsencrypt-prod`.

### Partie 5 — HPA avec metrics-server
- metrics-server est déjà actif (partie 1, `kubectl top` fonctionne).
- Vérifier le passage de 1 à 3 replicas sous charge et mesurer la RAM consommée.

### Partie 6 — NetworkPolicies
- Vérifier que les NetworkPolicies sont réellement appliquées (trafic interdit bloqué, trafic autorisé passant).

### À reprendre au déploiement de `app` (constats de la partie 3)
- Redirection HTTP → HTTPS et en-tête HSTS (`docs/cadrage.md` §1.4.14) : HTTP en clair encore servi.
- IP réelle du client perdue par ServiceLB : piste `externalTrafficPolicy: Local` sur le service Traefik, à vérifier
  avant de mettre en place le rate limiting sur `POST /annonces`.
- Limites de cert-manager : celles de `docs/adr/0001-conserver-cert-manager.md`.

### Nettoyage en fin d'expérience
- Supprimer le namespace `sandbox` (whoami, Ingress, Secrets TLS et toute la stack de la partie 2).
- Supprimer `ClusterRole` et `ClusterRoleBinding` `sandbox-prometheus` (hors namespace, voir partie 2).
- cert-manager, son `HelmChart` dans `kube-system` et les deux `ClusterIssuer` sont **conservés** pour `app`
  (`docs/adr/0001-conserver-cert-manager.md`).

### Rapport final
Contenu attendu (`docs/cadrage.md` §4, d'après `consignes.md` §1.2.2 — E1) : environnement, étapes pour
reproduire, difficultés, limites, résultat (adoption / rejet).
