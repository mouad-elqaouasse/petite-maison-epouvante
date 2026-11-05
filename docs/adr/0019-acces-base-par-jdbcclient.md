# ADR 0019 — Accès à la base par `JdbcClient`, sans JPA

- Statut : acceptée
- Date : 2026-11-05
- Source : US1a, décision D2 (validée le 05/11/2026) ; `docs/cadrage.md` §1.4.18 (budget RAM) et §1.4.17 (démarrage)

## Contexte

- `troc-service` enregistre et lit une seule table (`annonce`), avec des requêtes simples (insertion, liste des
  publiées triée par date). `notification-service` aura deux tables du même ordre (US2, US3).
- `spring-boot-starter-jdbc` est déjà présent (SQ-04) ; le schéma est géré par Flyway (ADR 0013).
- JVM limitée à 384 Mio (`troc-service`, jusqu'à 3 replicas) ; démarrage d'environ 30 s à 500m de CPU sur le VPS.

## Décision

Accès à la base par **`JdbcClient`** (Spring Framework) : requêtes SQL écrites dans un dépôt par table
(`AnnonceDepot`), lignes converties à la main en `record`. Pas de JPA ni d'Hibernate, pas de Spring Data JDBC.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| JPA / Hibernate | dépendance lourde (mémoire et temps de démarrage), comportements implicites (cache, chargement paresseux) sans besoin pour une table |
| Spring Data JDBC | dépôts générés commodes, mais une couche de plus pour deux requêtes ; correspondance des `enum` et du `uuid` à configurer |

## Conséquences

- Aucune nouvelle dépendance ; SQL visible et relu en PR ; contraintes de la table (`check`) alignées sur les règles de l'API.
- Le code de conversion des lignes est écrit et testé à la main (tests d'acceptation sur PostgreSQL réel, Testcontainers).
- Même choix attendu pour `notification-service` (US2, US3).
