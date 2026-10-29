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
| 4. Keycloak (issuer public / JWKS interne) | **Fait** (2026-10-28) |
| 5. HPA (1 → 3 replicas sous charge, RAM consommée) et application des NetworkPolicies | **Fait** (2026-10-29) |

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

### Partie 4 — Keycloak exposé en HTTPS (faite)
Objectifs :
1. Keycloak en mode production derrière Traefik, avec le certificat HTTPS de la partie 3.
2. Console d'administration non publique (accessible seulement par tunnel depuis le poste ; `docs/cadrage.md` §1.4.14).
3. Un realm de test avec un client public (PKCE, Proof Key for Code Exchange) et deux comptes de test (un auteur, un abonné).
4. Piège **issuer public / JWKS interne** (`docs/cadrage.md` §1.2.9) : un pod du cluster récupère les clés par l'adresse
   interne et valide un jeton émis pour l'adresse publique.
5. RAM de Keycloak après ces changements.

#### Méthode et choix (validés avant la première commande)
- Fichiers (`experimentations/exp1/`) :

| Fichier | Rôle |
|---|---|
| `20-keycloak.yaml` (modifié) | `KC_HOSTNAME=https://auth.epouvante.menuly-app.com` (issuer fixe), `KC_HOSTNAME_ADMIN=http://localhost:8180` (console par tunnel), `KC_PROXY_TRUSTED_ADDRESSES=10.42.0.0/16` (en-têtes `X-Forwarded-*` acceptés seulement depuis les pods), suppression de `KC_HOSTNAME_STRICT=false`, `strategy: Recreate` |
| `70-keycloak-ingress.yaml` | Ingress `keycloak` (seulement `/realms/epouvante-test` et `/resources`), middlewares Traefik de redirection HTTPS et HSTS (ajoutés à l'étape 6) |
| `71-realm-epouvante-test.json` | Realm : rôles `USER` / `MODERATEUR`, client public `epouvante-front` (flux standard seul, PKCE `S256`, pas d'octroi par mot de passe), audiences `troc-service` et `notification-service`, access token de 5 min, rotation du refresh token, protection contre la force brute, comptes `auteur-test` et `abonne-test` sans mot de passe |
| `72-configurer-realm.sh` | Crée le realm avec `kcadm.sh` dans le pod ; mots de passe lus dans un Secret et passés par l'entrée standard, jamais affichés |
| `73-verifier-jetons.sh` | Flux PKCE complet par l'URL publique, cas négatifs, vérification du jeton dans un pod éphémère (`smallstep/step-cli:0.31.0`) |

- **Nouvel Ingress `keycloak`** plutôt que de modifier `whoami-auth` (nom clair dans le manifest), en reprenant le Secret
  `auth-epouvante-tls` : aucune réémission attendue (quota de 5 certificats identiques par semaine, ADR 0001).
- **`strategy: Recreate`** : une mise à jour progressive lancerait un deuxième Keycloak de 768Mi avec 1,4 à 1,5 Gio disponibles.
- **Chemins exposés** : recommandation de Keycloak (guide « Using a reverse proxy ») : `/realms/` et `/resources/` exposés ;
  `/admin/`, `/realms/master/`, `/health`, `/metrics` internes. Ici, seul le realm de test est exposé.
- **Vérification du jeton par `step-cli`** (`step crypto jwt verify --jwks --iss --aud`) : image Alpine avec `curl` et
  `jq`, utilisateur non-root (uid 1000). Le resource server Spring sera testé avec US1a (Testcontainers).
- **Aucun mot de passe affiché** : mot de passe admin lu dans l'environnement du pod ; mots de passe de test générés sur le
  VPS (`openssl rand -hex 24`) dans le Secret `keycloak-test-users` ; le mot de passe admin pour la console est récupéré
  par moi-même, hors de la session Claude Code.
- Contournement des guillemets supprimés par PowerShell (partie 2, B) : commandes longues placées dans des scripts.

#### Étape 0 — Vérifications préalables (poste local, rien sur le VPS)
- Options confirmées dans la référence de configuration de Keycloak (version 26.8.1 listée) : `hostname-admin`
  (`KC_HOSTNAME_ADMIN`), `proxy-trusted-addresses` (`KC_PROXY_TRUSTED_ADDRESSES`, effective seulement avec `proxy-headers`).
- `step-cli` : `v0.31.0`, publiée le 2026-09-25 ; Dockerfile lu (Docker Desktop arrêté) : Alpine, `bash curl jq`, `USER step`.
- JSON du realm validé, scripts vérifiés par `bash -n`.
- Calcul du `code_challenge` PKCE vérifié sur l'exemple de la RFC 7636 (annexe B) : `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`.
  Difficulté : un premier essai avec un vecteur cité de mémoire (faux) a donné une valeur différente ; le vecteur exact
  a été relu dans le texte de la RFC.

#### Étape 1 — État de départ
```powershell
ssh ubuntu@51.178.39.218 'date -u && kubectl -n sandbox get pods,ingress,certificate,certificaterequest && kubectl top nodes && free -h'
```
Résultat (15:09:52 UTC) : 6 pods `Running` dans `sandbox`, 0 redémarrage ; Ingress `whoami-auth` et `whoami-epouvante` ;
certificats `Ready`, seules les demandes `-2` (production) restent (cert-manager ne garde que la dernière).
```
vps-c67c8a44   404m   20%   2312Mi   60%
Mem:           3.7Gi       2.3Gi       131Mi        45Mi       1.7Gi       1.5Gi
Swap:          2.0Gi        82Mi       1.9Gi
```

#### Étape 2 — Transfert, Secret des comptes de test, validation côté serveur
```powershell
scp experimentations/exp1/20-keycloak.yaml experimentations/exp1/70-keycloak-ingress.yaml experimentations/exp1/71-realm-epouvante-test.json experimentations/exp1/72-configurer-realm.sh experimentations/exp1/73-verifier-jetons.sh ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'kubectl -n sandbox create secret generic keycloak-test-users --from-literal=AUTEUR_PASSWORD=$(openssl rand -hex 24) --from-literal=ABONNE_PASSWORD=$(openssl rand -hex 24) && kubectl apply --dry-run=server -f ~/exp1/20-keycloak.yaml -f ~/exp1/70-keycloak-ingress.yaml'
```
Résultat : empreintes SHA-256 identiques sur le poste et le VPS ; `secret/keycloak-test-users created` (clés
`ABONNE_PASSWORD`, `AUTEUR_PASSWORD`, valeurs non lues) ; Deployment `configured`, Ingress `created` en `server dry run`.
Constat : le dry-run accepte l'Ingress `keycloak` alors que `whoami-auth` existe encore (aucun contrôle de conflit d'hôte ou
de Secret à l'admission) : la suppression préalable de `whoami-auth` reste indispensable.

#### Étape 3 — Keycloak en mode production
```powershell
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/20-keycloak.yaml && for i in $(seq 1 24); do echo --- $(date -u +%T); kubectl -n sandbox get pod -l app=keycloak --no-headers; kubectl -n sandbox top pod -l app=keycloak --no-headers 2>/dev/null; sleep 15; done; kubectl -n sandbox rollout status deploy/keycloak --timeout=60s && kubectl -n sandbox logs deploy/keycloak | grep -i -E hostname\|proxy\|started\|WARN | tail -n 20 && kubectl -n sandbox exec deploy/keycloak -- cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events && kubectl top nodes && free -h'
```

| Heure (UTC) | Âge | Prêt | CPU | RAM |
|---|---|---|---|---|
| 15:11:02 | — | ancien pod `Terminating` | 5m | 555Mi |
| 15:11:33 | 29 s | 0/1 | 985m | 274Mi |
| 15:12:04 | 61 s | 0/1 | 999m | 518Mi |
| 15:12:20 | 76 s | 0/1 | 967m | 149Mi |
| 15:12:51 | 107 s | **1/1** | 982m | 454Mi |
| 15:13:22 | 138 s | 1/1 | 25m | 484Mi |
| 15:16:59 | 355 s | 1/1 | 10m | 494Mi |

```
... Keycloak 26.8.1 on JVM (powered by Quarkus 3.40.1) started in 26.097s. Listening on: http://0.0.0.0:8080.
    Management interface listening on http://0.0.0.0:9000.
memory.peak 805306368 (768 Mio = limite) ; low/high/max/oom/oom_kill/oom_group_kill = 0
vps-c67c8a44   442m   22%   2537Mi   66%
Mem:           3.7Gi       2.2Gi       166Mi        47Mi       1.7Gi       1.5Gi
```
Constats :
- `Recreate` respecté : l'ancien pod s'arrête avant le démarrage du nouveau, jamais deux Keycloak en même temps.
- Prêt en **≈ 1 min 47 s** (2 min 36 s en partie 2) ; mêmes deux phases (JVM de construction, puis JVM du serveur) ;
  CPU à la limite d'1 cœur pendant le démarrage. Même pic à la limite (768 Mio) sans aucun événement mémoire.
- Avertissements sans gravité : fonctionnalités dépréciées actives par défaut (`identity-brokering-api:v1`,
  `twitter-broker:v1`), option `shouldAttachRoute` dépréciée, remarque OpenTelemetry.
- Keycloak 26 n'écrit pas sa configuration de nom d'hôte dans les logs. Contrôle séparé, par l'adresse interne :
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox exec deploy/prometheus -- wget -qO- http://keycloak.sandbox.svc:8080/realms/master/.well-known/openid-configuration | grep -o -E \"issuer\":\"[^\"]*\"'
```
```
"issuer":"https://auth.epouvante.menuly-app.com/realms/master"
```
  `KC_HOSTNAME` est appliqué : même interrogé par l'adresse interne, Keycloak annonce l'issuer public.

#### Étape 4 — Realm de test
```powershell
ssh ubuntu@51.178.39.218 'bash ~/exp1/72-configurer-realm.sh'
```
Résultat : `Created new realm with id 'epouvante-test'` ; réglages relus : `accessTokenLifespan` 300,
`revokeRefreshToken` true, `refreshTokenMaxReuse` 0, `bruteForceProtected` true, `registrationAllowed` false ;
client `epouvante-front` public, flux standard seul (implicite et octroi par mot de passe désactivés) ;
`auteur-test` et `abonne-test` : rôle `USER`, identifiant de type `password`.

Vérification complémentaire (lecture seule) de l'attribut PKCE et des mappers d'audience :
```
"pkce.code.challenge.method" : "S256"
audience-troc-service         : included.custom.audience=troc-service, access.token.claim=true, id.token.claim=false
audience-notification-service : included.custom.audience=notification-service, access.token.claim=true, id.token.claim=false
```
Constats :
- `kcadm.sh` fonctionne par `http://localhost:8080` dans le pod malgré le nom d'hôte fixé.
- Difficulté : avec `--fields`, `kcadm.sh` n'affiche le contenu d'un objet imbriqué que si ses sous-champs sont nommés
  (`attributes(...)`, `config(...)`) ; le premier affichage montrait `attributes : { }`. Affichage du script corrigé.
- Les comptes importés avec des rôles explicites n'ont pas le rôle composite `default-roles-epouvante-test`
  (`offline_access`, `uma_authorization`). Sans effet pour le POC.

#### Étape 5 — Bascule de l'Ingress vers Keycloak
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox get secret auth-epouvante-tls -o jsonpath={.metadata.resourceVersion} && echo && kubectl -n sandbox delete ingress whoami-auth && kubectl apply -f ~/exp1/70-keycloak-ingress.yaml && sleep 30 && date -u && kubectl -n sandbox get ingress,certificate,certificaterequest && kubectl -n sandbox get secret auth-epouvante-tls -o jsonpath={.metadata.resourceVersion} && echo && kubectl -n sandbox describe certificate auth-epouvante-tls | grep -E Not\ After\|Renewal\ Time\|Issuer'
```

| Contrôle (15:26:42 UTC) | Avant | Après |
|---|---|---|
| Ingress de `auth.` | `whoami-auth` | `keycloak` |
| Version du Secret `auth-epouvante-tls` | 4572 | **4572** |
| Nouvelle `CertificateRequest` pour `auth.` | — | **aucune** |
| `Not After` / `Renewal Time` | — | 2027-01-26T13:46:10Z / 2026-12-27T13:46:10Z |

Constats :
- **Aucune réémission** : le `Certificate` recréé par le nouvel Ingress a repris le certificat valide du Secret existant.
- La demande `auth-epouvante-tls-2` a disparu avec l'ancien `Certificate` (dont elle dépendait) : l'historique des
  demandes de `auth.` n'est plus visible dans le cluster, il reste dans ce journal (partie 3, étape 5).

#### Étape 6 — Exposition publique (depuis le poste)
```bash
for u in /realms/epouvante-test/.well-known/openid-configuration /realms/epouvante-test/protocol/openid-connect/certs /resources/ /realms/master/.well-known/openid-configuration /realms/master/protocol/openid-connect/auth /admin/ /admin/master/console/ /health/ready /metrics /; do printf '%-60s %s\n' "$u" "$(curl -sS -o /dev/null -w '%{http_code}' https://auth.epouvante.menuly-app.com$u)"; done
echo | openssl s_client -connect auth.epouvante.menuly-app.com:443 -servername auth.epouvante.menuly-app.com 2>/dev/null | openssl x509 -noout -issuer -subject -dates
```

| Chemin (HTTPS, 15:28:33 UTC) | Code | Répondu par |
|---|---|---|
| `/realms/epouvante-test/.well-known/openid-configuration` | 200 | Keycloak |
| `/realms/epouvante-test/protocol/openid-connect/certs` | 200 | Keycloak |
| `/resources/` | 404 | (aucun fichier ciblé) |
| `/realms/master/...` (découverte, autorisation) | 404 | Traefik (`404 page not found`) |
| `/admin/`, `/admin/master/console/` | 404 | Traefik |
| `/health/ready`, `/metrics`, `/` | 404 | Traefik |

- Document de découverte public : `issuer`, `authorization_endpoint`, `token_endpoint`, `jwks_uri` en
  `https://auth.epouvante.menuly-app.com/realms/epouvante-test/...`.
- Certificat présenté : `CN=YR1` (Let's Encrypt production), `CN=auth.epouvante.menuly-app.com`, jusqu'au 2027-01-26.

**Problème : page de connexion servie en HTTP clair.** Une demande d'autorisation sur `http://auth...` affiche le formulaire
de connexion (`200`, `kc-form-login`), alors que le realm est en `sslRequired: external`.

1. **Première hypothèse** : Keycloak voit tous les clients sous `10.42.0.1` (adresse source traduite par ServiceLB, constat de
   la partie 3), une adresse privée qu'il considère comme locale ; `external` n'impose HTTPS qu'aux adresses externes.
2. **Correction tentée** : realm passé en `sslRequired: all` (JSON et `kcadm.sh update realms/epouvante-test -s sslRequired=all`).
   Résultat : **aucun changement**, le formulaire est toujours servi en HTTP (`200`).
3. **Test qui invalide l'hypothèse** : la même demande, envoyée depuis le cluster directement à Keycloak (sans Traefik), en HTTP,
   puis avec l'en-tête `X-Forwarded-Proto: http`, affiche toujours le formulaire. Avec `all`, l'adresse du client n'entre
   plus en compte : l'IP privée n'est donc pas la cause.
4. **Vraie cause** : `KC_HOSTNAME` est une URL complète en `https://`. Keycloak tire alors le schéma de cette URL, pas de la
   requête : il considère **toute** requête comme HTTPS, et `sslRequired` (quelle que soit sa valeur) ne refuse plus rien.
   Indice dans la page : le formulaire servi en HTTP envoie ses données à `action="https://auth.epouvante.menuly-app.com/..."`.
5. **Risque réel** : le mot de passe part bien en HTTPS (adresse du formulaire), mais une page HTTP peut être modifiée en
   chemin (adresse du formulaire remplacée par un attaquant placé sur le réseau). Le refus du HTTP doit donc se faire avant
   Keycloak, dans Traefik.
6. **Correction retenue** : middlewares Traefik sur l'Ingress `keycloak` seulement (`70-keycloak-ingress.yaml`) :
   `redirectScheme` (`https`, `permanent: true`) et `headers` HSTS (`stsSeconds: 31536000`, sans `includeSubDomains` ni
   `preload`). `sslRequired: all` est conservé (sans effet aujourd'hui, utile si le nom d'hôte cessait d'être fixe).
   Version de Traefik lue sur le cluster : `3.7.13`, CRD `middlewares.traefik.io` en `v1alpha1`.

Vérification préalable (après `sslRequired: all`) : le document de découverte et le JWKS restent servis en HTTP par l'adresse
interne (`HTTP/1.1 200 OK`) : les services pourront lire les clés par `http://keycloak.sandbox.svc:8080`.

Application :
```powershell
scp experimentations/exp1/70-keycloak-ingress.yaml ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'kubectl apply --dry-run=server -f ~/exp1/70-keycloak-ingress.yaml && kubectl apply -f ~/exp1/70-keycloak-ingress.yaml && date -u && kubectl -n sandbox get middlewares.traefik.io,ingress,certificaterequest && kubectl -n sandbox get secret auth-epouvante-tls -o jsonpath={.metadata.resourceVersion}'
```
Résultat (15:35:08 UTC) : deux `Middleware` créés, Ingress `configured`, aucune nouvelle `CertificateRequest`, Secret
toujours en version 4572.

Vérification depuis le poste (15:35:24 UTC) :

| Requête | Résultat |
|---|---|
| `GET http://auth.../realms/epouvante-test/protocol/openid-connect/auth?...` | **301** vers la même URL en `https://` (paramètres conservés) |
| `GET http://auth.../.well-known/openid-configuration` | **301** vers `https://`, sans en-tête `Strict-Transport-Security` |
| `POST http://auth.../protocol/openid-connect/token` | **308** vers `https://` (méthode conservée) |
| `GET http://auth.../admin/` | 404 (chemin non routé, pas de redirection) |
| `GET https://auth.../...auth?...` | **200**, formulaire `kc-form-login`, `Strict-Transport-Security: max-age=31536000` |
| `GET http://auth...` en suivant les redirections | 200 en HTTPS après **1** redirection |
| `http://epouvante.menuly-app.com` (whoami) | 200, sans redirection ni HSTS : non touché |

Constats :
- **HTTP refusé pour `auth.`** : toute requête routée est redirigée vers HTTPS ; HSTS n'est envoyé qu'en HTTPS.
- `permanent: true` donne 301 pour `GET` et 308 pour `POST` (la méthode et le corps sont conservés).
- Limite : la validation HTTP-01 lors du renouvellement (prévu le 2026-12-27) n'est pas testée. Le routeur du solveur de
  cert-manager est plus spécifique et sans ces middlewares, et Let's Encrypt suit les redirections : pas de blocage attendu.

#### Étape 7 — Console d'administration par tunnel
Tunnel ouvert depuis le poste (le port-forward écoute sur la boucle locale du VPS, le tunnel SSH l'amène sur le poste) :
```bash
ssh -o ExitOnForwardFailure=yes -L 8180:localhost:8180 ubuntu@51.178.39.218 'kubectl -n sandbox port-forward svc/keycloak 8180:8080'
```
Premier constat (sans mot de passe) : `http://localhost:8180/admin/` → 302 vers `/admin/master/console/` → 200.

**Problème : la console affiche « Something went wrong, try again ».** Diagnostic avec Playwright (`playwright-core` 1.64.0,
Chrome installé sur le poste, en headless, par le tunnel) :
```
  0.9s [navigation] http://localhost:8180/admin/master/console/
  3.2s [HTTP 404] GET https://auth.epouvante.menuly-app.com/realms/master/protocol/openid-connect/3p-cookies/step1.html
  3.2s [console.error] Failed to load resource: the server responded with a status of 404 ()
```
Configuration injectée dans la page de la console :
```
"adminBaseUrl":  "http://localhost:8180"
"authUrl":       "http://localhost:8180"
"authServerUrl": "https://auth.epouvante.menuly-app.com"
"serverBaseUrl": "https://auth.epouvante.menuly-app.com"
```
Analyse :
- `KC_HOSTNAME_ADMIN` ne change que l'adresse de la console. La bibliothèque OIDC de la console se connecte au realm `master`
  par `authServerUrl`, c'est-à-dire l'URL de frontend du realm `master` (par défaut `KC_HOSTNAME`, publique).
- Sa première étape (test des cookies tiers, `3p-cookies/step1.html`) part donc vers `https://auth.../realms/master/...`,
  où Traefik répond 404 : `/realms/master` n'est volontairement pas exposé.
- Conclusion : **`KC_HOSTNAME_ADMIN` ne suffit pas quand `/realms/master` n'est pas public.**

Logs de Keycloak (hors `INFO`, 90 dernières minutes) :
```powershell
ssh ubuntu@51.178.39.218 'kubectl -n sandbox logs deploy/keycloak --since=90m | grep -v -E " INFO " | tail -n 40'
```
Aucune trace du 404 (il est renvoyé par Traefik, la requête n'atteint pas Keycloak). Seulement les avertissements du
démarrage (étape 3) et quatre `DefaultCookieProvider ... Non-secure context detected; cookies are not secured` à 15:28 et
15:31 : ce sont les requêtes HTTP de l'étape 6. Keycloak détecte donc bien le HTTP pour ses cookies, mais `sslRequired`
ne le refuse pas (étape 6, cause : `KC_HOSTNAME` en URL https complète).

Correction : URL de frontend du realm `master` = adresse du tunnel (réglage propre à ce realm, `epouvante-test` inchangé) :
```bash
ssh ubuntu@51.178.39.218 bash -s <<'EOF'
kubectl -n sandbox exec deploy/keycloak -- sh -c '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/k.cfg --server http://localhost:8080 --realm master --user admin --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null && /opt/keycloak/bin/kcadm.sh update realms/master -s attributes.frontendUrl=http://localhost:8180 --config /tmp/k.cfg && /opt/keycloak/bin/kcadm.sh get realms/master --fields "realm,sslRequired,attributes(frontendUrl)" --config /tmp/k.cfg; rm -f /tmp/k.cfg'
EOF
```
Résultat : connexion de `kcadm.sh` réussie, puis `HTTP 401 Unauthorized`. Vérification par le tunnel : la mise à jour est
bien appliquée (`authServerUrl` et `serverBaseUrl` = `http://localhost:8180`, issuer de `master` =
`http://localhost:8180/realms/master`). Le 401 vient de la relecture, faite avec le jeton obtenu **avant** le changement :
son `iss` (ancienne URL publique) ne correspond plus à l'issuer de `master`. Réglage ajouté **en dernière position** dans
`72-configurer-realm.sh` pour cette raison.

Nouvel essai Playwright, puis connexion admin (mot de passe lu dans `keycloak-secrets` par `ssh` et passé au navigateur par une
variable d'environnement du seul processus `node`, jamais affiché ni écrit) :

| Contrôle | Résultat |
|---|---|
| Ouverture de `http://localhost:8180/admin/` | redirection vers `http://localhost:8180/realms/master/protocol/openid-connect/auth`, page « Sign in to Keycloak », aucune requête en échec |
| Connexion `admin` | réussie, retour sur la console ; bandeau « You are logged in as a temporary admin user » |
| Realm `epouvante-test`, liste des clients | `epouvante-front` visible |
| Client `epouvante-front`, onglet *Settings* | `Require PKCE: On`, `PKCE Method: S256` |
| Adresse publique après correction | `/realms/master/...` (dont `3p-cookies`), `/admin/`, `/admin/master/console/` : toujours **404** ; issuer de `epouvante-test` inchangé (`https://auth...`) |

Constats :
- **Objectif 2 atteint** : console fonctionnelle par le tunnel seulement ; `/admin` et `/realms/master` restent non publics.
- Dans Keycloak 26.8, le réglage PKCE est affiché dans l'onglet *Settings* (section *Capability config*), plus dans *Advanced*.
- Avertissements de la console sans gravité : `iframe` avec `allow-scripts` et `allow-same-origin` (vérification de
  session de keycloak-js) ; `aria-label` manquant sur des groupes de navigation (accessibilité de la console elle-même).
- Difficultés du script Playwright : changer seulement l'ancre de l'URL (`#/epouvante-test/clients`) ne recharge pas le realm
  courant (rechargement nécessaire) ; capture « pleine page » inutile, le contenu défile dans un conteneur interne.
- Conséquences du réglage : l'issuer de `master` vaut `http://localhost:8180/realms/master`. Seuls l'administrateur et
  `kcadm.sh` utilisent ce realm. Un administrateur permanent reste à créer (limite du bac à sable).
- Durée du diagnostic : ≈ 15 min (limite fixée : 1 h, au-delà administration par `kcadm.sh` seul).
- Script de vérification conservé : `experimentations/exp1/74-verifier-console-admin.js` (sans secret ; contrôles notés
  `[OK]` / `[ECART]`). Version nettoyée du script de diagnostic, vérifiée par `node --check` mais pas rejouée (tunnel fermé).

Vérification de `kcadm.sh` après le changement (nouvelle connexion) :
```
realm master         : sslRequired external, attributes.frontendUrl http://localhost:8180
realm epouvante-test : sslRequired all
utilisateurs         : abonne-test, auteur-test
code de sortie : 0
```
`kcadm.sh` fonctionne toujours par `http://localhost:8080` dans le pod : le 401 ne concernait que le jeton obtenu avant le changement.

Fermeture du tunnel :
- Difficulté : l'arrêt de la commande `ssh` lancée en arrière-plan depuis Git Bash n'a pas arrêté `ssh.exe` (port 8180
  toujours ouvert sur le poste), arrêté ensuite par son numéro de processus. Côté VPS, le `kubectl port-forward` est
  resté orphelin (écoute sur `127.0.0.1:8180` et `[::1]:8180` du VPS seulement) ; arrêté par `kill`.
- Vérification : port 8180 libre sur le poste et sur le VPS.
- À retenir : ouvrir le tunnel dans un terminal au premier plan et le fermer par Ctrl+C, puis vérifier `ss -tlnp` sur le VPS.

#### Étape 8 — Issuer public / JWKS interne
Ajustement préalable de `73-verifier-jetons.sh` : `--pod-running-timeout=3m` sur `kubectl run` (1 min par défaut ; image à
télécharger au premier lancement, jeton valable 5 min). Script transféré, empreintes identiques.
```powershell
ssh ubuntu@51.178.39.218 'date -u; bash ~/exp1/73-verifier-jetons.sh; echo "code de sortie : $?"; date -u; kubectl -n sandbox get pod verif-jwt 2>&1 | tail -n 1'
```
Résultat (16:27:30 → 16:27:42 UTC, code de sortie 0) :

| Test (sur le VPS, par l'URL publique) | Attendu | Obtenu |
|---|---|---|
| A. Demande d'autorisation sans `code_challenge` | refus | `302` vers l'application avec `error=invalid_request` |
| B. Octroi par mot de passe sur `epouvante-front` | refus | `400`, `unauthorized_client` |
| C. Échange du code sans `code_verifier` | refus | `400`, `invalid_grant` |
| D. Échange du code avec un mauvais `code_verifier` | refus | `400`, `invalid_grant` |
| E. Flux PKCE complet | jeton | `200`, `Bearer`, `expires_in` 300, scopes `openid email profile` |

Dans le pod éphémère `verif-jwt` (`smallstep/step-cli:0.31.0`, limite 64Mi, uid 1000) :
```
JWKS par http://keycloak.sandbox.svc:8080/.../certs : HTTP 200
clés : kid L3-yLni6…  alg RSA-OAEP  use enc
       kid Ax1gCURV…  alg RS256     use sig
kid du jeton : Ax1gCURV… ; alg : RS256
claims : iss https://auth.epouvante.menuly-app.com/realms/epouvante-test
         aud ["troc-service","notification-service"]  azp epouvante-front  typ Bearer
         preferred_username auteur-test  roles ["USER"]  durée 300 s
```

| Vérification (`step crypto jwt verify --jwks <JWKS interne>`) | Attendu | Obtenu |
|---|---|---|
| `--iss` public, `--aud troc-service` | valide | **[OK] VALIDE** |
| `--iss` = adresse interne (le piège) | refus | **[OK] REFUSÉ** : `invalid issuer claim (iss)` |
| `--aud service-inconnu` | refus | [OK] REFUSÉ : `invalid audience claim (aud)` |
| Jeton altéré (rôle `MODERATEUR` ajouté, signature d'origine) | refus | [OK] REFUSÉ : `invalid signature` |

```
Document de découverte lu par l'adresse interne :
{"issuer":"https://auth.epouvante.menuly-app.com/realms/epouvante-test",
 "jwks_uri":"https://auth.epouvante.menuly-app.com/realms/epouvante-test/protocol/openid-connect/certs", ...}
Accès du pod à l'URL publique : HTTP 200 en 0.21 s
```
Pod supprimé à la fin (`pods "verif-jwt" not found`).

Constats :
- **Objectif 4 atteint** : un pod récupère les clés par l'adresse interne (HTTP) et valide un jeton émis pour l'adresse
  publique ; le même jeton est refusé si l'issuer attendu est l'adresse interne.
- **PKCE obligatoire et effectif** (objectif 3) : sans `code_challenge`, sans `code_verifier` ou avec un mauvais
  `code_verifier`, pas de jeton ; octroi par mot de passe refusé au client public.
- Audiences présentes (`troc-service`, `notification-service`), rôle `USER` dans `realm_access`, jeton de 5 min.
- Le document de découverte, même lu par l'adresse interne, annonce un `jwks_uri` **public** : un service qui ne
  s'appuierait que sur la découverte irait chercher les clés par l'extérieur. Conséquence pour les resource servers Spring
  (`docs/cadrage.md` §1.2.9) : `issuer-uri` = URL publique et `jwk-set-uri` = URL interne, fixée explicitement.
- L'URL publique est joignable depuis un pod (retour par l'IP publique du VPS, 0,21 s) ; le JWKS interne évite tout de même
  de dépendre du DNS public, de Traefik et du certificat pour valider les jetons.
- Effets sur Keycloak : 3 connexions de `auteur-test` (3 sessions dans le realm de test), aucun échec de mot de passe.
- Non testé ici : la configuration Spring elle-même (avec US1a et Testcontainers) ; le refresh token et sa rotation.

#### Étape 9 — RAM après les changements
```powershell
ssh ubuntu@51.178.39.218 'sleep 120 && date -u && kubectl -n sandbox get pods && kubectl -n sandbox top pods && echo --- keycloak && kubectl -n sandbox exec deploy/keycloak -- grep -E "^(anon|file|active_file|inactive_file) " /sys/fs/cgroup/memory.stat && kubectl -n sandbox exec deploy/keycloak -- cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.events && echo --- oom_kill && for d in postgres keycloak rabbitmq prometheus grafana; do echo $d $(kubectl -n sandbox exec deploy/$d -- grep "oom_kill " /sys/fs/cgroup/memory.events); done && kubectl -n cert-manager top pods && kubectl top nodes && free -h'
```
Résultat (16:30:53 UTC, 2 min après l'étape 8 ; Keycloak âgé de 79 min) :
```
6 pods Running dans sandbox, 0 redémarrage

grafana      24m   286Mi
keycloak     10m   590Mi
postgres      6m    46Mi
prometheus   10m    59Mi
rabbitmq    115m    84Mi
whoami        1m     3Mi

Keycloak : anon 556380160 (531 Mio) ; file 160198656 (153 Mio) ; active_file 56410112 (54 Mio) ;
           inactive_file 103788544 (99 Mio) ; memory.peak 805306368 (768 Mio) ; low/high/max/oom/oom_kill = 0
oom_kill   : postgres 0, keycloak 0, rabbitmq 0, prometheus 0, grafana 0
cert-manager : contrôleur 31Mi, cainjector 17Mi, webhook 15Mi

vps-c67c8a44   258m   12%   2546Mi   66%
Mem:           3.7Gi       2.3Gi       185Mi        50Mi       1.6Gi       1.4Gi
Swap:          2.0Gi        74Mi       1.9Gi
```

| Keycloak | Partie 2 (`start`, sans realm) | Partie 4 (nom d'hôte fixe, realm, 3 connexions, console) |
|---|---|---|
| `kubectl top` | 549 Mio | **590 Mio** |
| Mémoire propre (`anon`) | 534 Mio | **531 Mio** |
| Pic au démarrage (`memory.peak`) | 768 Mio (= limite) | 768 Mio (= limite) |
| Événements mémoire (`max`, `oom`, `oom_kill`) | 0 | **0** |
| Prêt en | 2 min 36 s | 1 min 47 s |

Constats :
- **Objectif 5** : mémoire propre de Keycloak **inchangée** (531 contre 534 Mio). Le realm de test, trois sessions et
  l'usage de la console n'ont pas fait grossir la JVM. Les +41 Mio de `kubectl top` viennent du cache de fichiers actif,
  récupérable par le noyau.
- Évolution depuis le redémarrage : 494 Mio à 6 min (étape 3), 590 Mio à 79 min.
- Marge de la mémoire propre avant la limite de 768Mi : ≈ 237 Mio. Limite suffisante au repos et pour quelques connexions ;
  à revérifier sous charge (nombreuses sessions) pendant les campagnes JMeter.
- Prometheus (40 → 59 Mio) et Grafana (232 → 286 Mio) augmentent avec le temps, comme prévu en partie 2.
- Nœud : 2312 → 2546 Mio (`kubectl top`) ; `free` : 1,5 → **1,4 Gio disponibles** sur toute la partie (seuil d'arrêt : 500 Mio).

#### Bilan de la partie 4

| Objectif | Résultat |
|---|---|
| 1. Keycloak en mode production derrière Traefik, certificat existant | **Atteint** : `start`, `KC_HOSTNAME` public fixe, TLS terminé par Traefik avec le certificat `letsencrypt-prod` de la partie 3 (aucune réémission) ; HTTP redirigé vers HTTPS, HSTS d'un an |
| 2. Console d'administration non publique | **Atteint** : `/admin` et `/realms/master` non routés (404 Traefik) ; console fonctionnelle par tunnel (`kubectl port-forward` + SSH) grâce à l'URL de frontend du realm `master` |
| 3. Realm de test, client public PKCE, deux comptes | **Atteint** : `epouvante-test`, `epouvante-front` (PKCE `S256` obligatoire, pas d'octroi par mot de passe), `auteur-test` et `abonne-test` (rôle `USER`) ; mots de passe générés sur le VPS, jamais affichés |
| 4. Issuer public / JWKS interne | **Atteint** : jeton validé dans un pod avec les clés lues par l'adresse interne et l'issuer public ; refusé avec l'issuer interne, une audience inconnue ou un rôle ajouté |
| 5. RAM de Keycloak | **Mesurée** : 590 Mio (`kubectl top`), 531 Mio de mémoire propre, pic à la limite de 768Mi sans événement mémoire |

| Mesure | Valeur |
|---|---|
| Démarrage de Keycloak (`Recreate`) | prêt en 1 min 47 s, serveur démarré en 26,1 s |
| Bascule de l'Ingress | sans réémission de certificat (Secret inchangé) |
| Vérification complète des jetons (`73-verifier-jetons.sh`) | 12 s, 9 résultats conformes |
| RAM disponible (`free`) | 1,5 Gio avant → 1,4 Gio après |

Difficultés :
- **HTTP clair servi malgré `sslRequired`** : avec `KC_HOSTNAME` en URL https complète, Keycloak traite toute requête comme
  HTTPS ; `sslRequired` (même `all`) est sans effet. Première hypothèse (IP privée vue par Keycloak) invalidée par un test
  direct. Correction dans Traefik (redirection + HSTS) (étape 6).
- **Console « Something went wrong »** : `KC_HOSTNAME_ADMIN` ne suffit pas, la console se connecte au realm `master` par son
  URL de frontend (publique par défaut, non routée). Corrigé par l'URL de frontend du realm `master` (étape 7).
- `kcadm.sh` : affichage des objets imbriqués seulement avec des sous-champs nommés ; jeton refusé (401) juste après le
  changement d'issuer de `master`.
- Tunnel : processus `ssh.exe` et `kubectl port-forward` survivant à l'arrêt de la commande lancée en arrière-plan.

Limites :
- Validation par un vrai resource server Spring non faite (US1a, Testcontainers) ; refresh token et rotation non testés.
- Administrateur de démarrage temporaire (bandeau de la console) ; pas d'administrateur permanent.
- Image Keycloak non optimisée (`kc.sh build` au démarrage) : pic à la limite et CPU à 1 cœur pendant le démarrage.
- Comportement mémoire sous charge non mesuré.
- Renouvellement du certificat avec la redirection HTTPS non observé (premier renouvellement le 2026-12-27).
- Rôles par défaut (`default-roles-epouvante-test`) absents des comptes importés.

### Partie 5 — HPA et NetworkPolicies (faite)
Les anciennes parties 5 (HPA) et 6 (NetworkPolicies) du plan sont traitées ensemble.

#### Reprise (état au 2026-10-29, 13:46 UTC)
- **Parties A (HPA) et B (NetworkPolicies) terminées** : cpu-demo supprimé ; tableau de bord « Démo HPA » toujours
  provisionné dans Grafana.
- **Règles appliquées dans `sandbox`** : 15 règles (fichiers 90 à 96), matrice à 0 écart après redémarrage des pods (B9).
- **Prochaine étape** : partie C (bilan final de l'expérience 1), puis nettoyage. Partie B commitée.
- **Nettoyage (décision du 2026-10-29)** : le namespace `sandbox` est **conservé** pour les expériences 2 et 3 ; seuls les
  deux pods `np-intrus` (namespaces `sandbox` et `default`) seront supprimés.
- **Points d'attention** :
  - `73-verifier-jetons.sh` corrigé : code de sortie non nul en cas d'écart dans le pod (non encore relancé sur le VPS).
  - Tunnels : `ssh -t` obligatoire. Scripts longs : `nohup` sur le VPS.

Objectifs :
1. HPA (Horizontal Pod Autoscaler) : application de démonstration consommant du CPU, 1 à 3 replicas, charge générée depuis le
   cluster ; temps de montée, temps de descente, RAM à 3 replicas. Seuil d'arrêt : RAM disponible < 500 Mio.
2. NetworkPolicies : vérifier que K3s les applique ; refus total dans `sandbox`, puis ouvertures une par une (DNS, Keycloak →
   PostgreSQL, Traefik → Keycloak, Prometheus → cibles) ; prouver qu'un pod non autorisé ne joint pas PostgreSQL et que
   Keycloak fonctionne toujours en HTTPS.
3. Bilan final de l'expérience 1 : adoption ou rejet, budget RAM pour la suite.

#### Méthode et choix (validés avant la première commande)

| Fichier (`experimentations/exp1/`) | Rôle |
|---|---|
| `80-hpa-demo.yaml` | `cpu-demo` : image `registry.k8s.io/hpa-example` (exemple de la documentation Kubernetes, épinglée par empreinte), requests 200m / 64Mi, limits 500m / 256Mi ; HPA `autoscaling/v2` 1 → 3, cible 50 % du CPU demandé, fenêtres par défaut (descente stabilisée sur 300 s) |
| `81-mesurer-hpa.sh` | Pod de charge `busybox:1.37.0` (200m), relevés toutes les 10 s (CSV), charge maintenue 120 s à 3 replicas, suivi de la descente ; arrêt automatique sous 500 Mio disponibles |
| `90` à `96-np-*.yaml` | Refus total, puis DNS, Keycloak → PostgreSQL, Traefik → Keycloak, Prometheus → cibles (API Kubernetes en deux règles : adresse du service et adresse du nœud), compléments (Grafana → Prometheus, Traefik → whoami et solveur ACME), pod `verif-jwt` |
| `97-np-intrus.yaml` | Pods `np-intrus` sans autorisation, dans `sandbox` et dans `default` (`postgres:18.6-alpine`, `pg_isready`) |
| `98-tester-flux.sh` | Matrice de 19 tests comparés à l'attendu de chaque étape : `strict` (nouvelle connexion), `intrus`, `sensible` (dépend de connexions déjà ouvertes, que le suivi de connexions du noyau peut laisser passer après un refus) |

- Application de démonstration non représentative d'une JVM : ce test valide le mécanisme et ses délais, pas la RAM de
  `troc-service` à 3 replicas.
- Limite mémoire portée de 128Mi (protocole initial) à 256Mi : plusieurs processus Apache sous charge, un OOM fausserait la mesure.
- Matrice de référence lancée **avant** toute règle : un test qui échoue déjà sans règle révèle un outil manquant, pas un blocage.

#### Étape 0 — Transfert et contrôles (lecture seule)
```bash
scp experimentations/exp1/8[01]-* experimentations/exp1/9[0-8]-* ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'cd ~/exp1 && sha256sum 8[01]-* 9[0-8]-* && kubectl -n kube-system get deploy metrics-server && kubectl top nodes && kubectl -n sandbox top pods && free -h && (grep -c -E "disable-network-policy" /etc/systemd/system/k3s.service || true) && sudo iptables-save | grep -c -E "KUBE-ROUTER|KUBE-NWPLCY" && kubectl get ns kube-system --show-labels && kubectl -n kube-system get pods -l "app.kubernetes.io/name=traefik" --show-labels && ... && kubectl apply --dry-run=server -f 80-hpa-demo.yaml -f 90-np-refus-total.yaml ... -f 97-np-intrus.yaml'
```
Résultat (16:48:42 UTC) :

| Contrôle | Résultat |
|---|---|
| Empreintes SHA-256 poste / VPS | identiques (11 fichiers) |
| metrics-server | `1/1`, `kubectl top` fonctionne |
| Nœud | 390m CPU (19 %), 2578Mi (67 %) ; `free` : **1,5 Gio disponibles**, swap 74 Mio |
| `disable-network-policy` dans le service K3s | absent (0) |
| Lignes `KUBE-ROUTER` / `KUBE-NWPLCY` dans `iptables-save` | 160 : contrôleur de NetworkPolicies intégré (kube-router) actif |
| Label du namespace `kube-system` | `kubernetes.io/metadata.name=kube-system` |
| Labels du pod Traefik | `app.kubernetes.io/name=traefik` (et `instance=traefik-kube-system`) |
| CoreDNS | `k8s-app=kube-dns`, service 53 UDP/TCP → port 53 du pod |
| Pod `svclb-traefik` | trouvé par `svccontroller.k3s.cattle.io/svcname=traefik` |
| Outils dans les pods | Prometheus, Grafana, Traefik : `wget`, `nc` (avec `-z`), `timeout` ; Keycloak : `/usr/bin/timeout` |
| Dry-run côté serveur | 25 objets acceptés |

Constats : labels et outils conformes aux hypothèses des fichiers, aucun ajustement nécessaire.

#### Étape A1 — Déploiement de cpu-demo
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/80-hpa-demo.yaml && kubectl -n sandbox rollout status deploy/cpu-demo --timeout=180s && sleep 45 && date -u && kubectl -n sandbox get hpa cpu-demo && kubectl -n sandbox top pod -l app=cpu-demo && free -h'
```
Résultat (17:06:48 UTC) :
```
cpu-demo   Deployment/cpu-demo   cpu: 0%/50%   1   3   1   62s
cpu-demo-66587c95-t5hh8   1m   24Mi
Mem:           3.7Gi       2.3Gi       165Mi        46Mi       1.6Gi       1.4Gi
Swap:          2.0Gi        76Mi       1.9Gi
```
Constats : le HPA lit la métrique CPU (`0%/50%`, pas `<unknown>`) ; un replica au repos : **24 Mio**, 1m de CPU.
RAM disponible : 1,4 Gio.

#### Étape A1 bis — Tableau de bord Grafana « Démo HPA » (demande ajoutée avant A2)
Fichiers : `51-dashboard-demo-hpa.json` (4 panneaux : pods cpu-demo en valeur et en courbe, CPU par pod avec repères 0,1
et 0,5 cœur, RAM disponible du nœud avec seuil à 500 Mio ; rafraîchi toutes les 5 s) ; `50-grafana.yaml` modifié
(`uid: prometheus` sur la source de données, fournisseur de tableaux de bord, montage du ConfigMap `grafana-dashboards`
généré depuis le JSON). Métriques cAdvisor seules, collecte inchangée à 30 s (décision de ne pas la réduire).
```bash
scp 50-grafana.yaml 51-dashboard-demo-hpa.json ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'cd ~/exp1 && kubectl -n sandbox create configmap grafana-dashboards --from-file=demo-hpa.json=51-dashboard-demo-hpa.json --dry-run=client -o yaml | kubectl apply -f - && kubectl apply -f 50-grafana.yaml && kubectl -n sandbox rollout status deploy/grafana --timeout=180s && ... wget .../api/search?query=HPA'
```
Résultat (17:07 UTC) :
- `grafana-dashboards` et `grafana-dashboard-provider` créés, Deployment mis à jour, nouveau pod prêt.
- Logs du nouveau pod : `inserting datasource from configuration name=Prometheus uid=prometheus`, puis
  `finished to provision dashboards`, sans erreur. (Les lignes `error ... context canceled` relevées par la commande venaient
  de l'ancien pod en cours d'arrêt.)
- API de recherche : tableau de bord `demo-hpa`, « Démo HPA », dans le dossier « Expérience 1 ».

Contrôle des trois requêtes dans Prometheus (17:08 UTC) :

| Requête | Résultat |
|---|---|
| Nombre de pods (`count(container_memory_working_set_bytes{…container="cpu-demo"})`) | `1` |
| CPU par pod (`rate(container_cpu_usage_seconds_total{…}[1m])`) | **vide** |
| RAM disponible (`machine_memory_bytes - working set de id="/"`) | 1 618 292 736 octets (1,51 Gio) |

Constats :
- **Problème : la requête CPU ne renvoie rien**, alors que le même filtre fonctionne pour la mémoire. Hypothèse : avec une
  collecte toutes les 30 s, une fenêtre `[1m]` ne contient pas toujours les 2 points nécessaires à `rate` (horodatages
  fournis par cAdvisor). Diagnostic à faire.
- RAM du panneau : 1,51 Gio contre 1,4 Gio pour `free` (`available`) : les deux calculs sont proches à ce moment-là.

Diagnostic (lecture seule) :
```bash
ssh ubuntu@51.178.39.218 'for q in "container_cpu_usage_seconds_total{namespace=\"sandbox\",container=\"cpu-demo\"}[3m]" "count_over_time(...[1m])" "count_over_time(...[2m])" "sum by (pod) (rate(...[2m]))"; do kubectl -n sandbox exec deploy/prometheus -- wget -qO- "http://localhost:9090/api/v1/query?query=$q"; done'
```
Résultat (≈ 17:09:43 UTC) :
- Horodatages des points sur 3 min : écarts de **34, 27, 26, 38 et 25 s** (horodatages fournis par cAdvisor, pas ceux de la
  collecte) ; série bien présente avec les labels `namespace`, `pod`, `container`, `cpu="total"`.
- Points dans la fenêtre : **2** sur 1 min (le minimum pour `rate`, parfois 1 selon le moment), **4** sur 2 min.
- `rate(...[2m])` : 0,00017 cœur, valeur attendue au repos.

Constats :
- **Hypothèse confirmée** : la fenêtre d'1 min est trop courte pour une collecte toutes les 30 s aux horodatages irréguliers.
  Règle habituelle : fenêtre d'au moins 4 fois l'intervalle de collecte.
- Correction : fenêtre `[2m]` dans `51-dashboard-demo-hpa.json` (version 2). Conséquence : le CPU affiché réagit avec environ
  1 min de retard sur la charge ; le HPA, lui, lit metrics-server et n'est pas concerné.

Déploiement de la correction (sans redémarrage de Grafana, qui relit ses fichiers toutes les 10 s) :
```bash
scp 51-dashboard-demo-hpa.json ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'cd ~/exp1 && kubectl -n sandbox create configmap grafana-dashboards --from-file=demo-hpa.json=51-dashboard-demo-hpa.json --dry-run=client -o yaml | kubectl apply -f - && for i in $(seq 1 18); do kubectl -n sandbox exec deploy/grafana -- grep -q "\[2m\]" /etc/grafana/dashboards/demo-hpa.json && break; sleep 10; done; ... /api/dashboards/uid/demo-hpa ...'
```
Résultat (17:12:34 UTC) : ConfigMap `configured` ; fichier mis à jour dans le pod après quelques dizaines de secondes (le
premier `grep`, avant la propagation, a renvoyé le code 1) ; l'API de Grafana renvoie `"version":2` et `[2m]`.
Constat : la mise à jour d'un ConfigMap monté n'est pas immédiate (synchronisation périodique du kubelet).

**Problème : le tunnel Grafana ne s'ouvre plus.** Un premier tunnel (`ssh -L 3000:localhost:3000 … 'kubectl -n sandbox
port-forward svc/grafana 3000:3000'`) a fonctionné à 16:58 ; après son arrêt, tous les essais suivants échouent avec
`Unable to listen on port 3000 … bind: address already in use`. Message de `kubectl`, donc côté VPS (port 3000 du poste libre).
```bash
ssh ubuntu@51.178.39.218 'ps -o pid,lstart,args -p 3855487; sudo ss -tlnp | grep ":3000 "; ...'
```
Résultat : processus 3855487 lancé à 16:58:35, à l'écoute sur `127.0.0.1:3000` et `[::1]:3000` : le `kubectl port-forward`
du premier tunnel, resté orphelin (même difficulté qu'en partie 4, étape 7). Il visait en plus l'ancien pod Grafana, remplacé
à 17:07.

Difficulté : la garde « ne tuer que si la ligne de commande contient `port-forward svc/grafana` » a empêché l'arrêt. `ps`
n'affiche que `kubectl` : sous K3s, `kubectl` est un lien vers le binaire `k3s`, qui réécrit le nom de son processus et masque
les arguments. Garde remplacée par le propriétaire du port :
```bash
ssh ubuntu@51.178.39.218 'sudo ss -tlnp | grep ":3000 " | grep -q "\"kubectl\",pid=3855487," && kill 3855487; sleep 2; sudo ss -tlnp | grep ":3000 " || echo "port 3000 libre sur le VPS"'
```
Résultat : `port 3000 libre sur le VPS`.

Correction pour la suite : tunnel ouvert avec `ssh -t` (terminal attribué à la session) ; à la fermeture (Ctrl+C ou coupure),
le `kubectl` distant reçoit le signal de fin de session et s'arrête avec elle :
```bash
ssh -t -o ExitOnForwardFailure=yes -L 3000:localhost:3000 ubuntu@51.178.39.218 'kubectl -n sandbox port-forward svc/grafana 3000:3000'
```

#### Étape A2 — Mesure du HPA (tableau de bord « Démo HPA » ouvert pendant le test)
Lancée depuis le poste, en arrière-plan :
```bash
ssh ubuntu@51.178.39.218 'bash ~/exp1/81-mesurer-hpa.sh'
```
Relevés (CSV `~/exp1/hpa-20261028T171722Z.csv` sur le VPS ; colonnes : heure, secondes depuis T0, phase, replicas voulus par
le HPA, replicas du Deployment, replicas prêts, CPU vu par le HPA en % de la request, RAM disponible en Mio) :
```
Avant la charge (17:17:22) : cpu-demo 1m / 11Mi ; nœud 526m (26 %), 2339Mi (61 %) ; free : 1,4 Gio disponibles
T0 (charge active) : 17:17:26
17:17:27,1,montee,1,1,1,0,1437
17:17:58,33,montee,1,1,1,0,1375
17:18:09,43,montee,3,3,3,245,1337
17:18:30,64,tenue,3,3,3,249,1365
17:18:41,75,tenue,3,3,3,98,1378
...                                      (tenue : CPU 80 à 97 %, RAM 1302 à 1378 Mio)
17:20:16,170,tenue,3,3,3,88,1302

RAM à 3 replicas (17:20:16) :
cpu-demo-...-9rt48   154m   12Mi
cpu-demo-...-t5hh8   205m   15Mi
cpu-demo-...-zrfjm   176m   12Mi
charge-cpu            15m    0Mi
grafana              34m   508Mi
keycloak              5m   546Mi
vps-c67c8a44   1010m   50%   2465Mi   64%
Mem:           3.7Gi       2.5Gi       125Mi        51Mi       1.5Gi       1.3Gi
Swap:          2.0Gi        76Mi       1.9Gi

Horodatage exact des pods (création, prêt) :
cpu-demo-...-9rt48   17:18:02Z   17:18:04Z
cpu-demo-...-zrfjm   17:18:02Z   17:18:04Z

Charge arrêtée : 17:20:20
17:20:30,184,descente,3,3,3,89,1344
17:20:51,205,descente,3,3,3,0,1344
...                                      (3 replicas, CPU 0 %, RAM 1277 à 1356 Mio)
17:24:43,437,descente,3,3,3,0,1339
client_loop: send disconnect: Connection reset by peer      (code de sortie ssh : 255)
```

**Problème : coupure de la connexion SSH** 263 s après l'arrêt de la charge, avant la fin de la fenêtre de stabilisation
(300 s). Le script s'est arrêté avec la session (dernier relevé à 17:24:43) ; le HPA a continué seul.

Récupération de la descente (lecture seule, le lendemain) :
```bash
ssh ubuntu@51.178.39.218 'pgrep -af mesurer-hpa || echo "script arrêté"; kubectl -n sandbox get hpa cpu-demo -o "jsonpath={.status.lastScaleTime} actuels={.status.currentReplicas} voulus={.status.desiredReplicas}"; kubectl -n sandbox get pods -l "app in (cpu-demo,charge-cpu)"; tail -n 2 ~/exp1/hpa-*.csv; kubectl -n sandbox get events --field-selector involvedObject.name=cpu-demo; kubectl -n sandbox exec deploy/prometheus -- wget -qO- "http://localhost:9090/api/v1/query_range?query=count(container_memory_working_set_bytes{namespace=\"sandbox\",container=\"cpu-demo\"})&start=1793208000&end=1793208900&step=15"; kubectl -n sandbox exec deploy/grafana -- grep -E "^(anon|file|active_file) " /sys/fs/cgroup/memory.stat; free -h'
```
Résultat (2026-10-29, 13:09:26 UTC) :
```
script : arrêté (seule la commande de contrôle correspond à pgrep)
HPA : lastScaleTime 2026-10-28T17:25:33Z, actuels=1, voulus=1
pods : cpu-demo-66587c95-t5hh8 seul (le pod d'origine), charge-cpu absent
événements : aucun (durée de conservation dépassée)
Prometheus, nombre de conteneurs cpu-demo : 3 de 17:20:00 à 17:30:15, puis 1 à partir de 17:30:30 (pas de palier à 2)
Grafana : anon 210 Mio, file 193 Mio (dont active_file 116 Mio)
Mem:           3.7Gi       2.3Gi       303Mi        45Mi       1.5Gi       1.5Gi
```

| Mesure | Valeur |
|---|---|
| Première décision du HPA (création des 2 pods) | **T0 + 36 s** (17:18:02), directement de 1 à 3 replicas |
| 3 replicas prêts | **T0 + 38 s** (17:18:04) |
| Retour à 1 replica après l'arrêt de la charge | **313 s** (17:20:20 → 17:25:33, `lastScaleTime`), directement de 3 à 1 |
| RAM d'un replica cpu-demo sous charge | 12 à 15 Mio |
| CPU du nœud à 3 replicas | 1010m (50 % des 2 vCPU) |
| RAM disponible (`free`) | 1,4 Gio avant → 1,3 Gio à 3 replicas ; minimum relevé 1277 Mio ; swap stable |

Constats :
- **Montée rapide** : la décision tombe dès la première lecture de metrics-server qui voit la charge (CPU à 245 % de la
  request) ; le HPA saute de 1 à 3 replicas (il en aurait voulu ≈ 5, plafond à 3). Les nouveaux pods sont prêts en 2 s
  (image déjà présente sur le nœud). Avec une JVM, il faudra ajouter le temps de démarrage de Spring Boot.
- À 3 replicas, le CPU reste au-dessus de la cible (80 à 98 % de la request contre 50 %) : le plafond de 3 limite la montée.
- **Descente** = fenêtre de stabilisation (300 s) + délai de lecture (≈ 13 s), en une seule étape : comportement par défaut
  attendu. Aucun palier à 2, ni dans `lastScaleTime` ni dans Prometheus.
- **Décalage du tableau de bord à la descente** : Prometheus voit encore 3 conteneurs **5 min** après la descente réelle
  (17:25:33 → 17:30:30). Cause : cAdvisor fournit ses propres horodatages, et Prometheus n'ajoute pas de marqueur
  d'obsolescence aux séries horodatées par la cible ; une série disparue reste visible pendant la fenêtre de recherche par
  défaut (5 min). Le panneau « Nombre de pods » est donc juste à la montée, en retard de 5 min à la descente.
  Pistes pour `app` : `kube_deployment_status_replicas` de kube-state-metrics (déjà prévu, `docs/cadrage.md` §1.4.16), ou
  l'option `track_timestamps_staleness: true` sur le job cAdvisor.
- **Grafana à 508 Mio** (`kubectl top`) pendant la démo, contre 288 Mio la veille : mémoire propre inchangée (210 Mio, contre
  ≈ 208 Mio en partie 2) ; la hausse est du cache de fichiers (193 Mio), récupérable.
- Limite de la mesure : application PHP de 12 à 15 Mio par replica, sans rapport avec une JVM (≈ 384 Mio prévus par replica
  de `troc-service`, `docs/cadrage.md` §1.4.18) ; le budget RAM de 3 replicas Spring Boot reste à mesurer avec le vrai service.
- Difficulté : coupure SSH pendant une mesure longue. À l'avenir, scripts longs lancés avec `nohup` sur le VPS et sortie
  écrite dans un fichier, lu à la fin.

#### Étape A4 — Suppression de la démonstration
```bash
ssh ubuntu@51.178.39.218 'kubectl delete -f ~/exp1/80-hpa-demo.yaml && sleep 20 && date -u && kubectl -n sandbox get pods && sudo ss -tlnp | grep ":3000 " || echo "port 3000 libre"; free -h'
```
Résultat (2026-10-29, 13:11:59 UTC) : Deployment, Service et HPA `cpu-demo` supprimés ; 6 pods dans `sandbox` (stack + whoami),
0 redémarrage ; port 3000 libre sur le VPS (tunnel Grafana fermé, aucun `kubectl` orphelin) ; **1,5 Gio disponibles**,
swap 85 Mio. Le tableau de bord « Démo HPA » reste provisionné dans Grafana, sans effet sur la suite.

#### Étape B1 — Pods intrus et matrice de référence (aucune NetworkPolicy)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/97-np-intrus.yaml && kubectl -n sandbox wait --for=condition=Ready pod/np-intrus --timeout=120s && kubectl -n default wait --for=condition=Ready pod/np-intrus --timeout=120s && bash ~/exp1/98-tester-flux.sh reference; echo "code de sortie : $?"'
```
Résultat (13:13:45 UTC) : deux pods `np-intrus` prêts (`sandbox` et `default`) ; **19 écarts sur 19** (tous `BLOQUE`), alors
que l'état des pods affiché par le même script montre Keycloak prêt.

Analyse :
- Un test contredit directement par l'état des pods (`keycloak prêt` : `BLOQUE`, colonne `PRET` : `true`) : la cause est le
  script, pas le réseau.
- **Cause** : chaque test est lancé par `timeout 20 "$@"`, et tous passent par une fonction du script (`dans`, `http_ok`,
  `keycloak_pret`, `up_job`). `timeout` ne lance que des programmes : il échoue sans rien exécuter
  (`failed to run command ... No such file or directory`, code 127), sortie masquée par `>/dev/null 2>&1`. Reproduit sur le poste.
- Correction de `98-tester-flux.sh` : fonctions et variable `NS` exportées (`export -f`), test lancé par
  `timeout 20 bash -c '"$@"' _ "$@"` ; vérifié sur le poste (fonction exécutée, code 0).
- Constat : la matrice de référence a joué son rôle (un test qui échoue sans aucune règle révèle un défaut de l'outil).

Nouvelle matrice de référence avec le script corrigé :
```bash
scp 98-tester-flux.sh ubuntu@51.178.39.218:~/exp1/
ssh ubuntu@51.178.39.218 'sha256sum ~/exp1/98-tester-flux.sh && bash ~/exp1/98-tester-flux.sh reference; echo "code de sortie : $?"'
```
Résultat (13:15:42 UTC) : empreinte identique (`04e48ff1…756c`) ; **19 `[OK]`, 0 écart**, code de sortie 0. Tous les flux
passent sans règle, y compris les intrus vers PostgreSQL (par nom et par IP, depuis `sandbox` et `default`) et vers Keycloak.
Tous les pods prêts, 0 redémarrage. Chaque test sait donc réussir : un blocage observé ensuite viendra des règles.

#### Étape B2 — Refus total (`90-np-refus-total.yaml`)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/90-np-refus-total.yaml && sleep 45 && bash ~/exp1/98-tester-flux.sh refus; echo "code de sortie : $?"; kubectl -n sandbox get events --field-selector reason=Unhealthy --sort-by=.lastTimestamp | tail -n 5'
```
Résultat (13:20:07 UTC, 45 s après l'application) : **0 écart** ; les 12 tests `strict` et `intrus` sont `BLOQUE`
(DNS compris) ; les 7 tests `sensible` :

| Test sensible | Obtenu |
|---|---|
| Keycloak prêt (sonde, base comprise) | PASSE |
| HTTPS public `auth.` | BLOQUE |
| `up{job=keycloak}` | PASSE |
| `up{job=rabbitmq}` | BLOQUE |
| `up{job=kubernetes-cadvisor}` | PASSE |
| HTTPS public `epouvante.` | BLOQUE |

Tous les pods prêts, 0 redémarrage ; aucun événement `Unhealthy`.

Constats :
- **K3s applique les NetworkPolicies** : toute nouvelle connexion vers ou depuis un pod de `sandbox` est refusée, y compris
  depuis un autre namespace (`default`) et depuis Traefik (`kube-system`).
- **Les sondes du kubelet passent** malgré le refus total : kube-router laisse passer le trafic venant du nœud lui-même.
- **Les connexions déjà établies survivent au refus** (hypothèse du protocole confirmée) : Keycloak reste prêt alors qu'une
  nouvelle connexion vers PostgreSQL est refusée (sa sonde de disponibilité passe par le pool de connexions ouvert avant la
  règle) ; Prometheus collecte encore Keycloak et cAdvisor par des connexions gardées ouvertes, mais plus RabbitMQ (connexion
  refermée entre-temps puis refusée). Traefik et whoami ne répondent plus : leurs connexions ont été rouvertes.
- Conséquence pour `app` : une règle trop stricte peut passer inaperçue tant que les pods ne redémarrent pas. Après chaque
  ajout de règle, valider par de nouvelles connexions (matrice) et par un redémarrage des pods concernés.

#### Étape B3 — DNS (`91-np-dns.yaml`)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/91-np-dns.yaml && sleep 10 && bash ~/exp1/98-tester-flux.sh dns; echo "code de sortie : $?"'
```
Résultat (13:22:38 UTC) : **0 écart**. Seul le DNS de l'intrus passe ; tous les autres tests `strict` et `intrus` restent
`BLOQUE` ; tests `sensible` identiques à B2. Pods prêts, 0 redémarrage ; règles `refus-total` et `sortie-dns`.

Constat : l'intrus résout `postgres.sandbox.svc.cluster.local`, mais sa connexion est refusée, par nom comme par IP :
le blocage vient bien de la règle sur PostgreSQL, pas du DNS.

#### Étape B4 — Keycloak → PostgreSQL (`92-np-keycloak-postgres.yaml`)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/92-np-keycloak-postgres.yaml && sleep 10 && bash ~/exp1/98-tester-flux.sh keycloak-postgres; echo "code de sortie : $?"'
```
Résultat (13:27:02 UTC) : **0 écart** ; règles `keycloak-sortie-postgres` et `postgres-entree-keycloak`. Nouvelle connexion
Keycloak → PostgreSQL et sonde de Keycloak : `PASSE` ; les 4 tests de l'intrus vers PostgreSQL (`sandbox` et `default`, par
nom et par IP) : `BLOQUE`. HTTPS `auth.` et `epouvante.` toujours coupés (attendu). Pods prêts, 0 redémarrage.
(Le script numérote ses propres étapes : « Étape 3 » à l'affichage pour B4, et ainsi de suite.)

#### Étape B5 — Traefik → Keycloak (`93-np-traefik-keycloak.yaml`)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/93-np-traefik-keycloak.yaml && sleep 10 && bash ~/exp1/98-tester-flux.sh traefik-keycloak; echo "code de sortie : $?"'
```
Résultat (13:28:16 UTC) : **0 écart** ; règle `keycloak-entree-traefik`. Nouvelle connexion Traefik → Keycloak:8080 et HTTPS
public `auth.` (découverte OIDC) : `PASSE` ; intrus toujours bloqué vers Keycloak et PostgreSQL. Pods prêts, 0 redémarrage.

#### Étape B6 — Prometheus → cibles (`94-np-prometheus-cibles.yaml`)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/94-np-prometheus-cibles.yaml && sleep 10 && bash ~/exp1/98-tester-flux.sh prometheus; echo "code de sortie : $?"'
```
Résultat (13:30:05 UTC) : **0 écart** ; 5 règles créées (cibles, deux règles API, entrées Keycloak et RabbitMQ). Prometheus
joint Keycloak:9000, RabbitMQ:15692 et l'API:443 ; `up=1` pour les trois jobs, `rabbitmq` compris (coupé depuis B2).
Grafana → Prometheus et Traefik → whoami toujours bloqués (attendu jusqu'à B7). Pods prêts, 0 redémarrage.

Test des deux règles API, une par une :

| Règle API conservée | Commande | Heure (UTC) | `prometheus -> API:443` (nouvelle connexion) | Écarts |
|---|---|---|---|---|
| Service seul (`10.43.0.1:443`) | `kubectl -n sandbox delete networkpolicy prometheus-sortie-api-noeud`, puis matrice | 13:31:46 | `BLOQUE` | 1 |
| Nœud seul (`51.178.39.218:6443`) | `kubectl apply -f ~/exp1/94-…` puis `delete networkpolicy prometheus-sortie-api-service`, puis matrice | 13:32:46 | `PASSE` | 0 |

Constats :
- **La règle de sortie s'applique après la traduction d'adresse** du service `kubernetes.default` : seule l'adresse réelle de
  l'API K3s (IP du nœud, port 6443) doit être autorisée ; la règle vers l'adresse du service est inutile.
- Avec le service seul, `up{job=kubernetes-cadvisor}` restait à 1 : connexion de collecte ouverte avant la suppression
  (même phénomène qu'en B2).
- Correction de `94-np-prometheus-cibles.yaml` sur le poste (validée) : règle `prometheus-sortie-api-service` retirée, en-tête
  mis à jour ; fichier recopié sur le VPS avec B7. Le cluster était déjà dans cet état.

#### Étape B7 — Compléments (`95-np-complements.yaml`)
```bash
scp experimentations/exp1/94-np-prometheus-cibles.yaml ubuntu@51.178.39.218:~/exp1/ && ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/95-np-complements.yaml && sleep 10 && bash ~/exp1/98-tester-flux.sh complements; echo "code de sortie : $?"'
```
Résultat (13:36:32 UTC) : **0 écart** ; 4 règles créées (`grafana-sortie-prometheus`, `prometheus-entree-grafana`,
`whoami-entree-traefik`, `solveur-acme-entree-traefik`). **19 tests `[OK]`**, plus aucun `[INFO]` : HTTPS public `auth.` et
`epouvante.` rétablis. 13 règles actives ; pods prêts, 0 redémarrage.

#### Étape B8 — Pod `verif-jwt` sous refus total (`96-np-verif-jwt.yaml` et `73-verifier-jetons.sh`)
```bash
ssh ubuntu@51.178.39.218 'kubectl apply -f ~/exp1/96-np-verif-jwt.yaml && sleep 5 && bash ~/exp1/73-verifier-jetons.sh; echo "code de sortie : $?"' && curl -sS -o /dev/null -w 'auth. : %{http_code}\n' https://auth.epouvante.menuly-app.com/realms/epouvante-test/.well-known/openid-configuration && curl -sS -o /dev/null -w 'epouvante. : %{http_code}\n' https://epouvante.menuly-app.com/
```
Résultat : règles `verif-jwt-sortie` et `keycloak-entree-verif-jwt` créées ; tests A à E (sur l'hôte, URL publique)
identiques à la partie 4 ; HTTPS `auth.` et `epouvante.` : 200 depuis le poste. **1 écart** dans le pod :

- Le premier accès aux clés par l'adresse interne (`keycloak.sandbox.svc:8080/.../certs`) est refusé en 10 ms ; `jwks.json`
  absent, donc `issuer public + clés internes` : `REFUSE` (attendu `VALIDE`). Les 3 autres vérifications sont `[OK]` pour
  une mauvaise raison (fichier de clés manquant) : faux positifs.
- Quelques instants plus tard, le même pod lit le document de découverte par la même adresse interne : la règle est correcte.
- **Hypothèse** : les règles d'un pod neuf sont appliquées par kube-router après son démarrage ; pendant ce délai, seul le
  refus total s'applique.
- Le script se termine avec le code 0 malgré l'écart (code de sortie du pod = dernière commande) : à corriger.

Modification de `73-verifier-jetons.sh` (validée) : lecture des clés réessayée chaque seconde, jusqu'à 30 s, avec affichage
du délai avant le premier succès. Relance :
```bash
scp experimentations/exp1/73-verifier-jetons.sh ubuntu@51.178.39.218:~/exp1/ && ssh ubuntu@51.178.39.218 'bash ~/exp1/73-verifier-jetons.sh; echo "code de sortie : $?"; kubectl -n sandbox get pod verif-jwt 2>&1 | tail -n 1'
```
Résultat : clés « obtenu après 1 s d'attente (essai n° 2) » ; **4 vérifications `[OK]` pour les bonnes raisons** (jeton
valide avec issuer public et clés internes ; refus pour issuer interne, audience inconnue, signature invalide) ; tests A à E
conformes ; pod `verif-jwt` supprimé (`NotFound`).

Constats :
- **Hypothèse confirmée** : un pod neuf subit le refus total pendant environ 1 s après son démarrage.
- La règle `service → Keycloak (JWKS interne)` des resource servers de `app` fonctionne sous refus total.
- Accès du pod à l'URL publique (information) : refusé en 12 ms. La règle vers l'IP publique / `svclb-traefik` ne
  correspond pas ; sans conséquence, les services utilisent l'adresse interne.

#### Étape B9 — Redémarrage des pods (règles validées sur des connexions neuves)
```bash
ssh ubuntu@51.178.39.218 'kubectl -n sandbox rollout restart deploy/keycloak deploy/prometheus && kubectl -n kube-system rollout restart deploy/traefik && kubectl -n sandbox rollout status deploy/keycloak --timeout=5m && kubectl -n sandbox rollout status deploy/prometheus --timeout=3m && kubectl -n kube-system rollout status deploy/traefik --timeout=3m && sleep 60 && bash ~/exp1/98-tester-flux.sh complements; echo "code de sortie : $?"; free -m'
```
Résultat (matrice à 13:45:28 UTC) : trois redémarrages terminés, **0 écart**, 19 tests `[OK]`. Nouveaux pods Keycloak et
Prometheus prêts, 0 redémarrage (aucune boucle) ; 15 règles actives. RAM : **1 562 Mio disponibles**, swap 82 Mio.

Constats :
- Les règles suffisent sur des connexions neuves : les tests `sensible` ne doivent plus rien aux connexions ouvertes avant B2.
- Keycloak redémarré joint PostgreSQL malgré le délai d'environ 1 s des pods neufs (B8).
- RAM au niveau de la partie 4 (1,4 à 1,5 Gio) : coût des NetworkPolicies non mesurable à cette échelle.

#### Bilan de la partie B
- K3s (kube-router) applique les NetworkPolicies : refus total puis ouvertures une par une, 0 écart final.
- Un pod non autorisé ne joint pas PostgreSQL (par nom ou par IP, depuis `sandbox` ou `default`) ; Keycloak reste joignable
  en HTTPS et la vérification des jetons fonctionne sous refus total.
- Trois pièges mesurés : connexions établies non coupées par une nouvelle règle (B2) ; règle API à écrire sur l'IP du nœud,
  port 6443 (B6) ; environ 1 s de refus total au démarrage d'un pod neuf (B8).

### À reprendre au déploiement de `app` (constats des parties 3 à 5)
- Resource servers Spring : `issuer-uri` = URL publique, `jwk-set-uri` = URL interne fixée explicitement (partie 4, étape 8).
- Keycloak : URL de frontend du realm `master` sur l'adresse du tunnel ; seul le realm applicatif exposé ; administrateur
  permanent à créer à la place de l'administrateur de démarrage ; image optimisée (`start --optimized`) à étudier.
- Redirection HTTP → HTTPS et en-tête HSTS (`docs/cadrage.md` §1.4.14) : HTTP en clair encore servi.
  Fait pour `auth.` dans `sandbox` (partie 4, étape 6 : middlewares Traefik) ; à reprendre pour tous les Ingress de `app`.
  Ne pas compter sur `sslRequired` de Keycloak tant que `KC_HOSTNAME` est une URL https complète.
- IP réelle du client perdue par ServiceLB : piste `externalTrafficPolicy: Local` sur le service Traefik, à vérifier
  avant de mettre en place le rate limiting sur `POST /annonces`.
- Limites de cert-manager : celles de `docs/adr/0001-conserver-cert-manager.md`.
- Dashboard « Montée en charge » : nombre de replicas lu dans kube-state-metrics, pas compté sur les séries cAdvisor
  (retard de 5 min à la descente, partie 5, étape A2) ; fenêtres `rate` d'au moins 4 fois l'intervalle de collecte.
- Tunnels (`kubectl port-forward` par SSH) : toujours avec `ssh -t`, sinon le `kubectl` distant survit à la fermeture.
- NetworkPolicies (partie 5, partie B) : règle de sortie vers l'API Kubernetes sur l'IP du nœud, port 6443 (pas l'adresse du
  service) ; après chaque nouvelle règle, valider par de nouvelles connexions et par un redémarrage des pods concernés.
- Démarrage des services sous refus total : environ 1 s pendant laquelle toute connexion est refusée (partie 5, étape B8).
  `troc-service` et `notification-service` doivent réessayer leurs premières connexions (PostgreSQL, RabbitMQ, JWKS de Keycloak)
  au lieu d'échouer.
- `73-verifier-jetons.sh` : code de sortie désormais non nul en cas d'écart dans le pod (partie F) ; les tests A à D
  affichent les codes sans les comparer à un attendu.

### Nettoyage en fin d'expérience
- Pods `np-intrus` (namespaces `sandbox` et `default`) **supprimés** le 2026-10-29 à 14:03:47 UTC (`kubectl delete -f ~/exp1/97-np-intrus.yaml`) ;
  6 pods restants dans `sandbox`, 0 redémarrage ; 1 602 Mio disponibles, swap 81 Mio.
- Namespace `sandbox` **conservé** pour les expériences 2 et 3 (décision du 2026-10-29), donc aussi le `ClusterRole` /
  `ClusterRoleBinding` `sandbox-prometheus` utilisé par sa stack.
- cert-manager, son `HelmChart` dans `kube-system` et les deux `ClusterIssuer` sont **conservés** pour `app`
  (`docs/adr/0001-conserver-cert-manager.md`).

### Rapport final
Contenu attendu (`docs/cadrage.md` §4, d'après `consignes.md` §1.2.2 — E1) : environnement, étapes pour
reproduire, difficultés, limites, résultat (adoption / rejet).
Rédigé : `docs/experimentations/exp1-socle-k3s-rapport.md` (2026-10-29). **Expérience 1 terminée.**
