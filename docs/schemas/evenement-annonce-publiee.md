# Événement `AnnoncePubliee` — contrat, version 1

Sources : `docs/cadrage.md` §1.2 points 7, 8 et 10 ; ADR 0010 (topologie), ADR 0020 (ce contrat).
Producteur : `troc-service` (`EmissionAnnoncePubliee`). Consommateur : `notification-service`
(`ConsommateurAnnoncePubliee`, US3, ADR 0025 : ack après commit, `basic.reject`, DLQ après 4 livraisons).

## Acheminement

| Élément | Valeur |
|---|---|
| Exchange | `annonces` (topic, durable), déclaré par RabbitMQ au démarrage (`load_definitions`, ADR 0010) |
| Clé de routage | `annonce.publiee` |
| File du consommateur | `notification.annonce-publiee` (quorum, `x-delivery-limit=3`, DLQ `notification.annonce-publiee.dlq`) |
| Compte producteur | `troc` : écriture sur `annonces` seulement, aucune déclaration (`configure` = `^$`) |

## Propriétés du message

| Propriété AMQP | Valeur |
|---|---|
| `content_type` | `application/json` (`content_encoding` : `UTF-8`) |
| `delivery_mode` | 2 (persistant) |
| `message_id` | `annonceId` |
| `type` | `AnnoncePubliee` |
| en-tête `correlationId` | identifiant créé à la publication, repris dans les logs des deux services |

Pas d'en-tête de type Java (`__TypeId__`) : le consommateur ne dépend d'aucune classe du producteur.

## Corps (JSON)

```json
{
  "version": 1,
  "annonceId": "0b7f2c1e-8a9d-4c47-9a51-3f1d2e6b8c90",
  "categorie": "Films",
  "auteurId": "f3c1a2b4-…",
  "titre": "Affiche Halloween 1978",
  "creeLe": "2026-11-05T10:15:30.123456Z",
  "photoUrl": "https://res.cloudinary.com/<compte>/image/upload/v1/epouvante/abc123.jpg"
}
```

| Champ | Type | Règle |
|---|---|---|
| `version` | entier | `1`. Un changement incompatible crée une version 2 ; le consommateur refuse une version inconnue (DLQ) |
| `annonceId` | UUID | **clé anti-doublon** : avec le destinataire et le type, unicité `(membreId, annonceId, type)` côté consommateur (US3) |
| `categorie` | texte | libellé de la liste fermée (`Figurines`, `Films`, `BD`, `Jeux de société`, `Goodies`), comme `GET /categories` |
| `auteurId` | texte | `sub` du jeton de l'auteur (destinataire de la `CONFIRMATION`) |
| `titre` | texte | 3 à 80 caractères |
| `creeLe` | date ISO 8601, UTC | date de création de l'annonce (microseconde) |
| `photoUrl` | URL | URL publique de la photo |

## Garanties du producteur

- Émis **après le commit** de l'annonce (`@TransactionalEventListener(AFTER_COMMIT)`) : jamais d'événement pour une annonce
  non enregistrée ; une annonce refusée (400) n'émet rien.
- **Publisher confirm** attendu (5 s au plus) et retour si le message n'est routé vers aucune file (`mandatory`).
- En cas d'échec (refus, retour, délai dépassé, broker injoignable) : l'annonce reste publiée, l'échec est journalisé et
  compté (`annonces_publiees_emission_echecs_total`). Perte possible : limite assumée, outbox au plan de remédiation
  (`cadrage.md` §1.2 point 8 et §8).
- Livraison « au moins une fois » : le consommateur doit accepter un même `annonceId` plusieurs fois.
