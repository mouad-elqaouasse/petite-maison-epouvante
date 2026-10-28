# Expérience 1 — Installation de K3s sur le VPS (journal brut)

Journal des commandes et résultats, base du rapport de l'expérience 1.
Toutes les commandes sont lancées depuis le poste Windows via `ssh ubuntu@51.178.39.218 "..."`.

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
