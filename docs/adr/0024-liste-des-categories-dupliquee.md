# ADR 0024 — Liste des catégories copiée dans `notification-service`

- Statut : acceptée
- Date : 2026-11-05
- Source : US2, décisions D1 à D4 (validées le 05/11/2026) ; `docs/cadrage.md` §1.1 point 1, §1.2 point 7 ;
  ADR 0020 (contrat `AnnoncePubliee`)

## Contexte

- La liste des centres d'intérêt est fermée : Figurines, Films, BD, Jeux de société, Goodies (`cadrage.md` §1.1.1,
  `contexte.md` §1). Elle n'existait que dans `troc-service` (enum `Categorie`, `GET /api/troc/categories`).
- `notification-service` détient les abonnements (`cadrage.md` §1.2.7) et doit refuser une catégorie inconnue
  (US2, « Une catégorie inconnue est refusée » → 400).
- En US3, il notifie à partir de l'événement `AnnoncePubliee`, dont le champ `categorie` porte déjà le libellé
  (ADR 0020, `docs/schemas/evenement-annonce-publiee.md`). La notification doit marcher même si `troc-service` est
  arrêté : les messages attendent dans RabbitMQ (volume persistant, ADR 0018).

## Décision

1. **D1** — Enum `Categorie` **copiée** dans `notification-service` (mêmes codes, mêmes libellés, même ordre).
   `CategorieTest` fige les 5 libellés du contrat : une modification d'un seul côté casse le build de ce service.
   La référence commune est le contrat de l'événement (`docs/schemas/evenement-annonce-publiee.md`).
2. **D2** — Chemins `GET /api/notifications/abonnements`, `PUT` et `DELETE /api/notifications/abonnements/{categorie}`
   (routage par préfixe, ADR 0006) ; la catégorie est passée par son **libellé**, encodé dans l'URL
   (`Jeux%20de%20soci%C3%A9t%C3%A9`), comme dans `GET /categories` et dans l'événement.
3. **D3** — `PUT` et `DELETE` répondent **204** et sont idempotents (rejouer ne change rien, retirer une catégorie non
   suivie réussit) ; catégorie inconnue → **400** au format ProblemDetail.
4. **D4** — L'écran garde `GET /api/troc/categories` pour afficher les cases (US2, « jamais codée en dur ») ; si
   `troc-service` ne répond pas, l'écran affiche une erreur avec « Réessayer », sans effet sur les notifications.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| `notification-service` appelle `GET /api/troc/categories` | couplage synchrone : une panne de `troc-service` bloquerait la gestion des abonnements ; nouveau flux réseau entre services (NetworkPolicies, ADR 0004) |
| Module Maven partagé entre les deux services | lie les builds et les versions des deux services ; casse le filtrage des workflows par chemin (ADR 0009) |
| Catégories en table, alimentées par les événements | ne permet pas de valider un abonnement avant la première annonce d'une catégorie |
| Code technique (`JEUX_DE_SOCIETE`) dans l'URL | différent du libellé renvoyé par `GET /categories` et porté par l'événement |
| Exposer aussi `GET /api/notifications/categories` | plus de code, non exigé par US2 |

## Conséquences

- Aucun nouveau flux réseau : le navigateur passe par Traefik vers les deux services (flux existants).
- Ajouter ou renommer une catégorie demande de modifier les deux enums, les deux contraintes `check` des migrations
  et le contrat de l'événement, dans une même PR. Le test de chaque service fige sa liste ; aucun test automatique ne
  compare directement les deux services (limite acceptée : 5 valeurs stables, décidées au cadrage).
- Un libellé inconnu reçu dans un événement (versions déployées décalées) sera traité en US3 : rejet vers la DLQ,
  visible par l'alerte « DLQ > 0 » (`cadrage.md` §1.4.16).
