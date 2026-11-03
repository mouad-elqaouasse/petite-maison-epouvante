# SQ-10 — CodeQL, Dependabot et Trivy config

| | |
|---|---|
| Dépend de | SQ-03 (les trois composants existent) |
| Sources | `cadrage.md` §1.3.13 (SCA : Dependabot + Trivy fs ; Trivy config ajouté le 03/11/2026), §5 (Dependabot en Should) |

## But

Compléter l'analyse statique (CodeQL pour Java et TypeScript), la veille sur les dépendances et le contrôle des
mauvaises configurations des manifests et des Dockerfile.

## Contenu

- CodeQL : Java/Kotlin (build par `mvnw`) et JavaScript/TypeScript, sur PR et chaque semaine ; non bloquant au départ
  (SonarCloud reste la porte bloquante), à revoir après le premier passage.
- `.github/dependabot.yml` : Maven (deux services), npm (front), GitHub Actions, Docker ; regroupement des mises à jour
  pour limiter le nombre de PR.
- Trivy config (ajouté le 03/11/2026) : `deploy/` et les Dockerfile des trois composants, bloquant si critique ou haute.

## Décisions (validées le 03/11/2026)

| # | Décision | Raison |
|---|---|---|
| D1 | CodeQL en **configuration avancée** (workflow `codeql.yml`), pas la *default setup*. | Le build Java par `mvnw` n'est pas possible en *default setup* ; les deux modes ne peuvent pas coexister. |
| D2 | Trivy config dans un workflow à part (`securite-config.yml`), **sans filtre de chemin**, SARIF envoyé dans l'onglet Security ; `experimentations/` exclu. | Un contrôle obligatoire filtré par chemin resterait « en attente » sur les PR qui ne touchent pas ces chemins (SQ-11). |
| D3 | Exceptions dans `.trivyignore`, chaque règle avec sa justification ; manifests générés de Flux exclus s'ils ne relèvent que du code de l'éditeur. | Une exception sans raison écrite est de la dette cachée. |
| D4 | Dependabot sur les Dockerfile seulement, pas sur les images de `deploy/app/`. | Le tag de nos images dans `deploy/app/` est écrit par la CI (ADR 0009) ; Dependabot proposerait des PR concurrentes. |

## Critères d'acceptation

- Premier passage CodeQL visible dans l'onglet Security ; résultats triés (corrigés ou justifiés).
- Une première PR Dependabot passe par toute la CI.
- Une erreur critique ou haute de Trivy config fait échouer le contrôle ; `main` n'en contient aucune non justifiée.

## À faire par toi

Dans *Settings → Advanced Security* : Dependency graph, Dependabot alerts et Dependabot security updates activés ;
CodeQL laissé sur *Not set up* (configuration avancée). Dans *Settings → Secrets and variables → Dependabot* : ajouter
`SONAR_TOKEN` (les PR Dependabot ne lisent pas les secrets Actions). Après la fusion : ajouter le contrôle Trivy config
aux contrôles obligatoires du ruleset de `main` (SQ-11).

## Résultats (03/11/2026) — PR [#25](https://github.com/mouad-elqaouasse/petite-maison-epouvante/pull/25), en cours

- **Blocage démontré (critère 3)** : premier push (`1e73e97`, workflow seul, sans correctif) → contrôle `trivy-config`
  **en échec** sur une seule erreur, KSV-0047 (HIGH, `nodes/proxy` de Prometheus) :
  [exécution 41670269232](https://github.com/mouad-elqaouasse/petite-maison-epouvante/actions/runs/41670269232).
  Second push (`3e5ac33`, correctif ADR 0016) → `trivy-config` **vert** :
  [exécution 41670519891](https://github.com/mouad-elqaouasse/petite-maison-epouvante/actions/runs/41670519891).
- **Tri initial de Trivy config** (12 erreurs critiques ou hautes) : 9 dans les composants Flux générés (KSV-0041,
  KSV-0046) et 1 faux positif PostgreSQL (KSV-0109), en exception ; Keycloak KSV-0014 en exception jusqu'à SQ-12,
  **qui expire le 01/01/2027** (`expired_at` : passée cette date, le contrôle échoue) ; Prometheus KSV-0047 corrigé.
  Les Dockerfile n'ont aucune erreur critique ou haute.
- **Onglet Security** : 89 alertes Trivy non bloquantes (15 moyennes, 74 faibles), **triées pendant la phase
  sécurité** pour le plan de remédiation (`docs/cadrage.md` §8).
- **CodeQL sur la PR** : 0 résultat en Java/Kotlin (76 règles) et en JavaScript/TypeScript (87 règles) ;
  jobs de 1 min 7 s et 50 s. Le premier passage sur `main` reste à constater après la fusion.
- **Dependabot** : `.github/dependabot.yml` validé par GitHub (contrôle vert). Première PR Dependabot attendue
  après la fusion (critère 2).
- **Réglages GitHub** faits par toi le 03/11/2026 : Dependabot alerts et security updates, secret Dependabot
  `SONAR_TOKEN`. Contrôle `trivy-config` à ajouter au ruleset de `main` avec SQ-11 (repoussé en fin de phase).
- **Reste après la fusion** : Prometheus redémarré par Flux, cible `kubernetes-cadvisor` UP et RAM du nœud
  (`observabilite-verifier.sh`, `kubectl top`, `free -m`).
