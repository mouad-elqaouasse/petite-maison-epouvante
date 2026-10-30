# SQ-09 — SonarCloud (Quality Gate bloquant)

| | |
|---|---|
| Dépend de | SQ-02 (deux services à analyser) ; SQ-03 pour l'analyse du front |
| Sources | `cadrage.md` §1.3.11 (indicateur 2 : couverture ≥ 80 %, dette ≤ 5 %, note A), §1.3.13 (SAST), §1.4.15 (jeton dans les secrets GitHub Actions) |

## But

Le Quality Gate de SonarCloud bloque toute PR qui fait entrer de la dette dans `main`.

## Contenu

- JaCoCo dans les deux services (rapport XML), couverture du front (lcov).
- Analyse SonarCloud dans les workflows (un projet monorepo ou un projet par composant, à décider à l'ouverture du ticket),
  jeton `SONAR_TOKEN` lu depuis les secrets GitHub ; résultat de la Quality Gate attendu par la CI (bloquant).
- Quality Gate configurée avec les seuils de l'indicateur 2.

## Critères d'acceptation

- Une PR affiche le statut de la Quality Gate.
- Une PR de démonstration sous le seuil de couverture échoue (branche jetable, non fusionnée).

## À faire par toi

Créer l'organisation et le projet SonarCloud, désactiver l'analyse automatique, ajouter `SONAR_TOKEN` aux secrets du dépôt.
