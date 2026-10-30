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
3. **Flux** : suppression de la Kustomization `exp3-hello` ; `GitRepository` limité à `/deploy/` ;
   nouvelle Kustomization `app` (`path: ./deploy/app`, `prune: true`, `wait: true`, sans `targetNamespace` imposé
   si chaque manifest porte `namespace: app`). Les manifests de Flux et de cert-manager quittent `experimentations/`
   pour `deploy/flux/` et `deploy/cert-manager/` (appliqués à la main, comme à l'exp. 3 — ADR 0001, Suite).
   Valeurs mémoire de cert-manager ramenées à celles de l'ADR 0001.
4. **Suppression** du namespace `sandbox` et des ressources cluster `sandbox-*`.
5. **Mesure** de la RAM disponible après nettoyage (référence pour le budget de `app`).

## Critères d'acceptation

- `kubectl get ns sandbox` → introuvable ; aucune ressource `sandbox-*` au niveau du cluster.
- `kubectl get secret -n app` → `epouvante-tls` et `auth-epouvante-tls` présents, dates d'expiration inchangées.
- Kustomization `app` `Ready=True` sur un `deploy/app/kustomization.yaml` vide (aucune ressource).
- Aucune nouvelle `CertificateRequest` ni `Order` créée par la bascule (vérifié de nouveau à SQ-01 et SQ-06).
- RAM disponible notée dans le ticket.

## Risque

Si cert-manager réémet malgré tout (annotations incomplètes) : 1 émission sur 5 autorisées par semaine (ADR 0001),
acceptable mais à éviter. Repli : aucun, la réémission fonctionne (< 30 s, exp. 1).
