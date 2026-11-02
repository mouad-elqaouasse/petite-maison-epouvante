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

## Résultats (02/11/2026) — ticket clos

- **Décision** : un seul projet SonarCloud pour les trois composants, analysé par `sonarcloud.yml` sans filtre de
  chemin (ADR 0012). Offre gratuite : Quality Gate par défaut « Sonar way », indicateur 2 de `docs/cadrage.md` §1.3.11
  aligné sur ses conditions.
- **PR [#12](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/12)** (analyse, JaCoCo, LCOV, ADR 0012) :
  statut « SonarCloud Code Analysis » affiché sur la PR, Quality Gate **OK** ; le job `analyse` attend la Quality Gate
  (`sonar.qualitygate.wait=true`). Critère 1 rempli.
- **PR [#13](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/13)** (démonstration, branche jetable) :
  23 lignes Java non testées → Quality Gate **ERROR**, couverture du nouveau code 0,0 % (seuil 80 %), job `analyse` en
  échec ; PR fermée sans fusion, branche supprimée. Critère 2 rempli.
- **PR [#14](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/14)** (`sonar.java.libraries`) : jars des
  dépendances copiés par `dependency:copy-dependencies` ; l'avertissement « Unresolved imports/types » disparaît.
  Commit poussé après la fusion de la #12, d'où une PR séparée.
- **Analyse de `main`** (`50d7967`, 02/11/2026 13:40 UTC) : Quality Gate **OK** ; **295 lignes de code**, seulement
  `troc-service/src`, `notification-service/src` et `front/src` (CSS 106, TypeScript 84, HTML 41, Java 52,
  `application.properties` 12) ; couverture **90,4 %** (lignes 86,2 %) ; 0 bug, 0 vulnérabilité, 0 code smell,
  0 hotspot ; notes A. Les 10 940 lignes affichées avant la première analyse de `main` venaient d'une analyse antérieure.
- **Duplication globale 7,9 %** : `VersionController` et les classes `*Application` presque identiques dans les deux
  services. Sans effet sur la Quality Gate (seul le nouveau code compte), mais une PR qui recopierait du code d'un
  service à l'autre serait bloquée (≤ 3 %).
- **Durée** : job `analyse` ≈ 1 min 40 s par PR, en parallèle des workflows de composants.
- **Cluster** : aucun changement (CI seulement), pas de mesure de RAM.
- **Suite** : SQ-11 ajoutera le contrôle `analyse` (ou « SonarCloud Code Analysis ») aux contrôles obligatoires de `main`.
