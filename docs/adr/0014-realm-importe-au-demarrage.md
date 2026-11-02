# ADR 0014 — Realm `epouvante` importé au démarrage, mots de passe appliqués par script

- Statut : acceptée
- Date : 2026-11-02
- Source : ticket SQ-06 (`docs/tickets/SQ-06-keycloak-jetons.md`, Contenu) ; ADR 0003, 0008 ; exp. 1, partie 4
  (`experimentations/exp1/72-configurer-realm.sh`)

## Contexte

- Le realm (rôles, client public PKCE, mappers d'audience, durées des jetons, comptes de démonstration) doit être
  versionné dans le dépôt, mais le dépôt est public : aucun mot de passe dans le fichier (`CLAUDE.md`, Contraintes ;
  `docs/tickets/README.md`, « Mots de passe »).
- À l'exp. 1, le realm était créé par `kcadm.sh` dans un script lancé à la main.
- Keycloak tourne avec l'image standard (`start`), un seul pod, base `keycloak` persistante (SQ-04).

## Décision

1. `deploy/app/keycloak/realm-epouvante.json`, **sans aucun mot de passe**, est monté par une ConfigMap générée par
   kustomize et importé par `start --import-realm`.
2. **L'import n'a lieu que si le realm n'existe pas** (stratégie par défaut de Keycloak : un realm déjà présent est
   ignoré). Une modification ultérieure du JSON **n'est pas appliquée automatiquement** : elle doit être reportée à la
   main par `kcadm.sh` (ou le realm supprimé puis réimporté, ce qui efface sessions et mots de passe). La ConfigMap n'a
   pas de suffixe de hachage, pour qu'un changement du JSON ne redémarre pas Keycloak inutilement.
3. Les mots de passe sont générés sur le VPS dans des Secrets (`keycloak-secrets.sh`) puis appliqués par
   `deploy/scripts/keycloak-comptes.sh` : administrateur permanent du realm `master`, mots de passe des comptes de
   démonstration, suppression de l'administrateur de démarrage, puis URL de frontend du realm `master` sur l'adresse
   du tunnel (en dernier, ADR 0003). Script relançable sans effet de bord.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Realm créé par script seulement (comme à l'exp. 1) | configuration du realm hors du déploiement déclaratif ; une étape manuelle de plus après chaque base vide |
| Mots de passe dans le JSON (valeurs ou hachés) | dépôt public |
| Outil de synchronisation continue (keycloak-config-cli, opérateur Keycloak) | un pod ou un job de plus pour un realm qui change rarement ; RAM du VPS (ADR 0005) |
| ConfigMap avec suffixe de hachage | redémarrage de Keycloak (≈ 2 min d'indisponibilité) sans effet, le realm n'étant pas réimporté |

## Conséquences

- Base `keycloak` vide (volume perdu) : le realm revient seul au démarrage, puis `keycloak-comptes.sh` est relancé.
- Le JSON du dépôt peut diverger du realm en service : toute modification du realm se fait dans le JSON **et** par
  `kcadm.sh`, dans le même ticket.
- L'administrateur de démarrage n'est créé qu'avec le realm `master` (premier démarrage) : sa variable reste dans le
  manifest sans recréer le compte après sa suppression.
