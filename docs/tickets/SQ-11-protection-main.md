# SQ-11 — Protection de `main`

| | |
|---|---|
| Dépend de | SQ-03, SQ-09 (les contrôles requis existent) |
| Sources | `cadrage.md` §1.4.19 (`main` protégée, `github-actions[bot]` autorisé à contourner pour le commit du tag) ; rapport exp. 3 §7 ; ADR 0009 |

## But

Rien n'entre dans `main` sans PR et sans contrôles verts, et la CI peut toujours livrer un nouveau tag à Flux.

## Contenu

- Ruleset sur `main` : PR obligatoire, contrôles requis (gitleaks, build et tests, Trivy, Quality Gate), pas de
  force-push ni de suppression.
- Les contrôles filtrés par chemin ne doivent pas bloquer une PR qui ne touche pas leur composant (contrôle « toujours
  présent » ou règle adaptée).
- **Livraison du tag** : d'abord vérifier si le ruleset d'un dépôt personnel accepte GitHub Actions (ou une autre
  identité utilisée par la CI) dans sa liste de contournement. Sinon, appliquer l'option B ci-dessous.
  L'option retenue est consignée dans un ADR et reportée dans `cadrage.md` §1.4.19.

### Options étudiées pour le commit du tag

| Option | Principe | Avantages | Inconvénients |
|---|---|---|---|
| A. Contournement du ruleset | la CI pousse le tag sur `main`, autorisée à contourner la règle (prévu au cadrage) | aucune modification de la chaîne de l'exp. 3 ; tag visible dans `main` | dépend de ce que GitHub permet sur un dépôt personnel ; commits du bot dans l'historique de `main` |
| **B. Branche `deploy` surveillée par Flux** | `main` reste la source des manifests ; à chaque livraison, la CI recopie `deploy/` de `main` (au SHA livré) sur la branche `deploy`, y écrit le tag et pousse ; le `GitRepository` de Flux suit `deploy` au lieu de `main` | `main` entièrement protégée, sans exception ; historique de `main` sans commit du bot ; la branche `deploy` est l'état exact du cluster (retour arrière = revert sur `deploy`) | une modification de manifest sans nouvelle image doit aussi être recopiée (workflow sur `deploy/**` de `main`) ; deux branches à expliquer en soutenance ; le tag courant n'est plus lisible dans `main` ; concurrence des pushes toujours à sérialiser (ADR 0009) |
| C. Clé de déploiement (deploy key) en écriture | la CI pousse sur `main` avec une deploy key, si le ruleset autorise ce type d'acteur à contourner | proche de A | une clé en écriture à stocker dans les secrets GitHub et à faire tourner |

Points à vérifier pour B : la branche `deploy` ne doit accepter que les pushes de la CI (ruleset dédié ou, à défaut,
risque accepté sur un dépôt à un seul contributeur) ; la boucle de déclenchement reste impossible (push par `GITHUB_TOKEN`,
aucun workflow sur la branche `deploy`) ; Flux continue de lire en HTTPS anonyme (aucune clé dans le cluster).

## Critères d'acceptation

- Push direct sur `main` refusé ; PR avec un contrôle rouge non fusionnable.
- Après fusion d'une PR, le tag est livré (sur `main` ou `deploy` selon l'option) et Flux déploie le bon SHA.
- Une PR « docs » seule est fusionnable.

## À faire par toi

Créer le ou les rulesets (réglage réservé au propriétaire du dépôt), en suivant la configuration écrite dans ce ticket.
