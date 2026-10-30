# SQ-10 — CodeQL et Dependabot

| | |
|---|---|
| Dépend de | SQ-03 (les trois composants existent) |
| Sources | `cadrage.md` §1.3.13 (SCA : Dependabot + Trivy fs), §5 (Dependabot en Should) |

## But

Compléter l'analyse statique (CodeQL pour Java et TypeScript) et la veille sur les dépendances.

## Contenu

- CodeQL : Java/Kotlin (build par `mvnw`) et JavaScript/TypeScript, sur PR et chaque semaine ; non bloquant au départ
  (SonarCloud reste la porte bloquante), à revoir après le premier passage.
- `.github/dependabot.yml` : Maven (deux services), npm (front), GitHub Actions, Docker ; regroupement des mises à jour
  pour limiter le nombre de PR.

## Critères d'acceptation

- Premier passage CodeQL visible dans l'onglet Security ; résultats triés (corrigés ou justifiés).
- Une première PR Dependabot passe par toute la CI.

## À faire par toi

Activer Dependabot alerts / security updates et autoriser CodeQL dans les réglages du dépôt.
