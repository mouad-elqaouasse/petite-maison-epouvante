# ADR 0026 — Topologie RabbitMQ dans un fichier versionné, droits du compte `notification` réduits à sa file

- Statut : acceptée
- Date : 2026-11-06
- Complète : ADR 0010 (définitions chargées au démarrage du broker)
- Source : US3, décisions D4 et D5 (validées le 06/11/2026) ; `docs/cadrage.md` §1.2 point 10 (modifié le même jour)

## Contexte

- ADR 0010 : comptes, permissions et topologie sont écrits dans `deploy/scripts/rabbitmq-secrets.sh` (texte inclus
  dans le script), qui y ajoute les hachés des mots de passe générés sur le VPS.
- Les tests d'intégration de `notification-service` doivent prouver le comportement **avec la vraie topologie et les
  vrais droits** (4 livraisons puis DLQ, moindre privilège) ; une copie dans les tests pourrait diverger du script.
- Le compte `notification` avait la lecture sur sa file **et sur la DLQ** (`docs/cadrage.md` §1.2.10). Aucun code de
  la V1 ne lit la DLQ : ce droit ne sert à rien au service.

## Décision

1. Comptes, permissions, exchanges, files et liaisons sont dans **`deploy/scripts/rabbitmq-definitions.json`**, avec
   deux marqueurs à la place des hachés (`__HACHE_TROC__`, `__HACHE_NOTIFICATION__`) (D5).
2. `rabbitmq-secrets.sh` lit ce fichier (à côté du script) et remplace les marqueurs ; les tests de
   `notification-service` le lisent aussi et le chargent dans le RabbitMQ de Testcontainers (`load_definitions`), avec
   des hachés de mots de passe de test.
3. **Droits du compte `notification`** : `configure` = `^$`, `write` = `^$`, `read` = `^notification\.annonce-publiee$`.
   Plus de lecture sur la DLQ (D4). La lecture de la DLQ reste un geste d'administration.
4. La CI de `notification-service` se lance aussi quand ce fichier change.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Garder le texte dans le script, copie dans les tests | deux sources à tenir identiques ; un écart ne serait vu qu'au déploiement |
| Topologie déclarée par les tests avec un compte administrateur | ne prouve ni les droits ni les arguments réels de la file |
| Garder la lecture de la DLQ pour `notification` | droit inutile au service, contraire au moindre privilège (`docs/cadrage.md` §1.2.10) |

## Conséquences

- Mise en place sur le VPS : copier le script et le fichier ensemble, relancer `rabbitmq-secrets.sh` (mots de passe
  conservés), puis redémarrer `rabbitmq-0` pour recharger les définitions ; coupure courte, messages conservés par le
  volume persistant (ADR 0018).
- `deploy/scripts/rabbitmq-verifier-droits.sh` vérifie désormais que `notification` **ne lit pas** la DLQ.
- Un changement de topologie modifie un seul fichier, relu en PR et testé par la CI de `notification-service`.
