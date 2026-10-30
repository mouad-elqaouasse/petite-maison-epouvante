# ADR 0008 — Namespace `app` et Secrets créés à la main, hors de Flux

- Statut : acceptée
- Date : 2026-10-30
- Source : `docs/tickets/README.md` (décision D3), ticket SQ-00 ; rapport exp. 3 §8

## Contexte

- Flux applique `deploy/app/` avec `prune: true` : ce qui disparaît du dépôt est supprimé du cluster
  (`experimentations/exp3/flux/30-synchro.yaml`).
- Les Secrets ne sont jamais dans le dépôt public : ils sont créés à la main sur le VPS et cités par leur nom
  (`docs/cadrage.md` §1.4.15).
- Les Secrets TLS `epouvante-tls` et `auth-epouvante-tls` sont copiés de `sandbox` vers `app` pour éviter une nouvelle
  émission Let's Encrypt (ADR 0001, quota).
- Supprimer un namespace supprime tout son contenu, Secrets compris.

## Décision

1. Le namespace `app` est créé à la main (`kubectl create namespace app`) et **n'est pas déclaré dans `deploy/app/`**.
2. Les Secrets de `app` sont créés à la main sur le VPS ; tous les mots de passe sont générés sur le VPS (`openssl rand`)
   sans être affichés (décision du 2026-10-30).
3. Chaque manifest de `deploy/app/` porte `namespace: app` ; la Kustomization Flux `app` n'en gère que le contenu.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Namespace déclaré dans `deploy/app/` | un retrait (même accidentel) du fichier ferait supprimer par Flux le namespace avec les Secrets et les certificats copiés |
| Namespace dans le dépôt avec l'annotation de Flux qui empêche la suppression (`kustomize.toolkit.fluxcd.io/prune: disabled`) | protège, mais repose sur une annotation facile à perdre ; le gain (namespace versionné) est faible pour un seul namespace |
| Secrets chiffrés dans le dépôt (Sealed Secrets, SOPS) | hors périmètre V1 (`docs/cadrage.md` §6), au plan de remédiation |

## Conséquences

- Recréer l'environnement demande deux gestes manuels avant Flux : créer le namespace, puis les Secrets (procédure
  documentée dans le ticket SQ-00 et les tickets qui créent des Secrets).
- Aucune ressource déclarée par Flux ne peut supprimer les Secrets.
- Pas de rotation automatique des secrets (limite déjà notée au plan de remédiation, `docs/cadrage.md` §8).
