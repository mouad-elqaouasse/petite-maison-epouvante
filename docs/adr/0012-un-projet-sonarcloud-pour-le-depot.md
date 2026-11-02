# ADR 0012 — Un seul projet SonarCloud pour le dépôt

- Statut : acceptée
- Date : 2026-11-02
- Source : ticket SQ-09 (« un projet monorepo ou un projet par composant, à décider à l'ouverture du ticket ») ;
  `docs/cadrage.md` §1.3.11 (indicateur 2), §1.3.13 (SAST) ; ADR 0009

## Contexte

- Le dépôt contient trois composants (`troc-service`, `notification-service`, `front`), chacun livré par un workflow
  filtré par chemin (ADR 0009).
- SonarCloud est en offre gratuite : la Quality Gate par défaut « Sonar way » ne peut pas être remplacée par une gate
  personnalisée.
- La Quality Gate doit bloquer les PR (Pull Requests) ; SQ-11 en fera un contrôle obligatoire de `main`.

## Décision

Un seul projet SonarCloud (`mouad-elqaouasse_petite-maison-epouvante`) analyse les trois composants, configuré dans
`sonar-project.properties` à la racine. L'analyse a son propre workflow (`sonarcloud.yml`), **sans filtre de chemin**,
sur chaque PR et chaque push sur `main` : elle relance les tests des trois composants (couverture JaCoCo et LCOV),
puis attend le résultat de la Quality Gate (`sonar.qualitygate.wait=true`) et échoue si elle échoue.

Quality Gate « Sonar way », conditions sur le nouveau code : couverture ≥ 80 %, notes A en maintenabilité (ratio de
dette ≤ 5 %), fiabilité et sécurité, duplication ≤ 3 %, *security hotspots* revus à 100 %.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Un projet par composant, analysé dans chaque workflow de composant | un contrôle obligatoire issu d'un workflow filtré par chemin ne tourne pas sur les PR qui ne touchent pas ce composant et reste « en attente » (SQ-11) ; trois projets à créer et à régler, trois statuts par PR |
| Un seul projet, analysé seulement pour les composants modifiés | SonarCloud considère le code absent d'une analyse comme supprimé : les résultats de `main` deviendraient faux |
| Gate personnalisée aux seuils exacts du cadrage | indisponible en offre gratuite ; « Sonar way » contient ces seuils (couverture 80 %, note A = dette ≤ 5 %) et en ajoute |

## Conséquences

- Un seul statut de Quality Gate par PR, donc un seul contrôle obligatoire dans SQ-11.
- Les tests des trois composants tournent à chaque PR, en plus des workflows de composants : quelques minutes de CI
  (intégration continue) en plus, acceptables pour un POC.
- L'analyse sur `main` ne bloque pas la livraison : le code y arrive déjà validé par la PR.
- SonarCloud ignore les conditions de couverture et de duplication quand le nouveau code compte moins de 20 lignes.
- Les PR ouvertes depuis un fork n'ont pas accès à `SONAR_TOKEN` : l'analyse y échoue (dépôt sans contributeur externe).
