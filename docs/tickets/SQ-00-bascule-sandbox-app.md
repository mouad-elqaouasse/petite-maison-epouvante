# SQ-00 — Bascule `sandbox` → `app`

| | |
|---|---|
| Dépend de | — |
| Fait par | Claude (commandes VPS annoncées, exécutées après ton accord) ; manifests Flux dans le dépôt |
| Sources | `CLAUDE.md` (Environnements) ; `cadrage.md` §3 ; ADR 0001 (Conséquences, quota Let's Encrypt) ; rapport exp. 3 §9 |

## But

Libérer la RAM du bac à sable sans perdre les certificats, et préparer `app` pour Flux.

## Étapes

1. **Inventaire** (lecture seule) : `kubectl get all,secret,certificate,ingress,networkpolicy -n sandbox`, ressources
   cluster liées (`ClusterRole`/`ClusterRoleBinding` `sandbox-prometheus`), Kustomizations Flux, `free -m`.
2. **Copie des Secrets TLS** `epouvante-tls` et `auth-epouvante-tls` dans `app` (namespace créé à la main, ADR 0008) :
   export YAML, retrait de `namespace`, `uid`, `resourceVersion`, `creationTimestamp`, `ownerReferences` ;
   **conservation des annotations `cert-manager.io/*`** (émetteur `letsencrypt-prod`, noms DNS), pour que cert-manager
   reconnaisse le certificat quand l'Ingress de `app` le référencera, sans nouvelle émission.
   Les fichiers exportés restent sur le VPS (`chmod 600`, supprimés après usage), jamais dans le dépôt.
3. **Flux** : création du `GitRepository` (limité à `/deploy/`) et de la Kustomization `app` vers `deploy/app/`
   (les contrôleurs `source-controller` et `kustomize-controller` tournent déjà dans `flux-system` ; la Kustomization
   et le `GitRepository` de l'exp. 3 ont été supprimés à la fin de l'expérience — inventaire du 30/10/2026).
   Kustomization `app` : `path: ./deploy/app`, `prune: true`, `wait: true`, sans `targetNamespace` imposé
   si chaque manifest porte `namespace: app`. Les manifests de Flux et de cert-manager sont **copiés** de `experimentations/`
   vers `deploy/flux/` et `deploy/cert-manager/` (appliqués à la main, comme à l'exp. 3 — ADR 0001, Suite) ;
   les originaux restent dans `experimentations/`, auxquels les rapports renvoient pour la reproductibilité
   (décision du 30/10/2026). Valeurs mémoire de cert-manager ramenées à celles de l'ADR 0001 ; contrôleur à 128Mi
   (haut de la fourchette), à réévaluer après mesure avec les certificats de `app`.
4. **Suppression** du namespace `sandbox` et des ressources cluster `sandbox-*`.
5. **Mesure** de la RAM disponible après nettoyage (référence pour le budget de `app`).

## Inventaire (30/10/2026, lecture seule)

| Élément | Constat |
|---|---|
| `sandbox` | 6 pods Running (grafana, keycloak, postgres, prometheus, rabbitmq, whoami), 7 Secrets, 15 NetworkPolicies, 1 Ingress (`keycloak`), 1 Certificate (`auth-epouvante-tls`) |
| Cluster | `ClusterRole` et `ClusterRoleBinding` `sandbox-prometheus` ; aucune autre ressource `sandbox-*` |
| Flux | contrôleurs `source-controller` et `kustomize-controller` Running dans `flux-system` ; aucune Kustomization ni `GitRepository` |
| RAM avant nettoyage | `free -m` : 3 819 Mio total, 2 356 utilisés, **1 462 disponibles**, swap 79 Mio ; `kubectl top nodes` : 2 250 Mio (58 %) |
| cert-manager | **aucune** `CertificateRequest` ni `Order` (référence pour le critère « aucune nouvelle émission ») |
| `epouvante-tls` | Secret sans Certificate (Certificate probablement supprimé avec l'Ingress de whoami) ; annotations `cert-manager.io/*` complètes (`letsencrypt-prod`, `ClusterIssuer`) ; expire le **26/01/2027 13:46:12 UTC** |
| `auth-epouvante-tls` | Certificate Ready ; annotations `cert-manager.io/*` complètes ; expire le **26/01/2027 13:46:10 UTC** |

## Avancement

- **Étape 2 faite (30/10/2026)** : namespace `app` créé à la main ; `epouvante-tls` et `auth-epouvante-tls` copiés
  avec leur label `controller.cert-manager.io/fao` et leurs annotations `cert-manager.io/*` ; dates d'expiration
  inchangées (26/01/2027 13:46:12 et 13:46:10 UTC) ; aucune `CertificateRequest` ni `Order` créée ; fichiers exportés
  effacés du VPS (`shred -u`).
- **Étape 3 faite (30/10/2026)** : `HelmChart` de cert-manager appliqué avec les limites de l'ADR 0001 (consommation
  avant : contrôleur 33 Mio, cainjector 20 Mio, webhook 25 Mio) ; `GitRepository` `petite-maison` et Kustomization
  `app` `Ready=True` sur `main@sha1:fc3dece`.
- **Étape 4 faite (30/10/2026)** : namespace `sandbox` (volume `postgres-data` compris) et `ClusterRole`/
  `ClusterRoleBinding` `sandbox-prometheus` supprimés ; Secrets TLS de `app` intacts ; aucune `CertificateRequest`.
- **Étape 5 (30/10/2026)** : RAM disponible **2 280 Mio** (`free -m`, contre 1 462 avant, soit +818 Mio) ;
  `kubectl top nodes` : 1 715 Mio (44 %) ; swap 72 Mio.

## Critères d'acceptation

- `kubectl get ns sandbox` → introuvable ; aucune ressource `sandbox-*` au niveau du cluster.
- `kubectl get secret -n app` → `epouvante-tls` et `auth-epouvante-tls` présents, dates d'expiration inchangées.
- Kustomization `app` `Ready=True` sur un `deploy/app/kustomization.yaml` vide (aucune ressource).
- Aucune nouvelle `CertificateRequest` ni `Order` créée par la bascule (vérifié de nouveau à SQ-01 et SQ-06).
- RAM disponible notée dans le ticket.

## Risque

Si cert-manager réémet malgré tout (annotations incomplètes) : 1 émission sur 5 autorisées par semaine (ADR 0001),
acceptable mais à éviter. Repli : aucun, la réémission fonctionne (< 30 s, exp. 1).
