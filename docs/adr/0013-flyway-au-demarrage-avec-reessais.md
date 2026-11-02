# ADR 0013 — Flyway au démarrage, avec réessais

- Statut : acceptée
- Date : 2026-11-02
- Source : ticket SQ-04 ; ADR 0004 §4 (les services réessaient leurs premières connexions au lieu d'échouer) ;
  `docs/cadrage.md` §1.4.17 (`startupProbe` dimensionnée pour un démarrage d'environ 30 s)

## Contexte

- `troc-service` et `notification-service` ont chacun leur base, migrée par Flyway (outil de migration de schéma).
- Par défaut, Flyway migre la base pendant le démarrage de Spring Boot : si PostgreSQL ne répond pas, le démarrage échoue.
- PostgreSQL est un pod unique (`Recreate`) : pendant un redémarrage ou un déploiement, il est indisponible quelques secondes.
  Un service qui démarre à ce moment-là ne doit pas échouer (ADR 0004 §4).
- Une coupure de la base en cours de fonctionnement ne doit pas faire redémarrer les services : seul le trafic doit être retiré.

## Décision

1. **Flyway reste au démarrage**, avec réessais de la connexion : `spring.flyway.connect-retries` et
   `spring.flyway.connect-retries-interval`. Le budget de réessais couvre une indisponibilité courte de PostgreSQL
   (de l'ordre de la minute au plus) ; au-delà, le démarrage échoue et Kubernetes redémarre le pod.
2. **La `startupProbe` est allongée** pour couvrir ce budget en plus du démarrage normal (environ 30 s sur le VPS).
3. **L'indicateur de santé `db` est dans le groupe `readiness`, pas dans `liveness`** : une base coupée en cours de
   fonctionnement retire le pod du Service sans le redémarrer ; il redevient prêt dès que la base répond.
4. Le pool de connexions (HikariCP) a un délai d'attente court, pour que l'indicateur `db` réponde avant le délai
   de la sonde `readiness`.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| B. Migration en arrière-plan : le service démarre sans base, une tâche réessaie la migration, un indicateur `migration` bloque `readiness` jusqu'à sa fin | plus de code à écrire et à tester (tâche, état partagé, indicateur) pour un cas que les réessais couvrent ; risque de servir des requêtes sur un schéma non migré si l'indicateur est mal câblé ; complexité non justifiée dans un POC |
| Aucun réessai : laisser Kubernetes redémarrer le pod | chaque échec coûte un redémarrage complet de la JVM (environ 30 s à 500m de CPU) et les délais entre redémarrages croissent (`CrashLoopBackOff`) ; contraire à ADR 0004 §4 |
| `db` aussi dans `liveness` | une coupure de la base ferait redémarrer les services en boucle sans rien corriger |

## Conséquences

- Pendant les réessais, le service n'écoute pas encore : ses sondes ne répondent pas, d'où la `startupProbe` allongée.
- Une base absente plus longtemps que le budget fait échouer le démarrage : le pod est redémarré par Kubernetes,
  comportement acceptable et visible (`kubectl get pods`).
- Tests (SQ-04) : base indisponible pendant les premières secondes du démarrage, puis disponible → le service démarre ;
  base coupée en cours de fonctionnement → `readiness` DOWN, `liveness` UP, puis `readiness` UP à son retour.
- La durée réelle d'indisponibilité de PostgreSQL lors d'un redémarrage du pod est mesurée sur le VPS (SQ-04) ;
  le budget de réessais sera ajusté si elle le dépasse.
