# Base de données — organisation et modèle cible

Sources : `docs/cadrage.md` §1.1 (points 1 à 5), §1.2 (points 7 et 8), §1.4.18 ; `docs/backlog/US1a` à `US5` ;
ticket SQ-04 ; ADR 0013. Images pour les slides : `bdd-organisation.png`, `bdd-modele-cible.png` (même dossier).

## 1. Organisation des bases (SQ-04)

Une seule instance PostgreSQL 18 dans le namespace `app` (`docs/cadrage.md` §1.4.18), une base par service et
**un rôle par base** : chaque rôle est propriétaire de sa base et n'a aucun droit sur les autres (`CONNECT` retiré à
`PUBLIC` sur chaque base). Chaque mot de passe est dans son propre Secret Kubernetes, généré sur le VPS.

```mermaid
flowchart LR
    subgraph instance["Instance PostgreSQL 18 — pod unique, volume local-path"]
        admin(["rôle postgres<br/>(administrateur)<br/>Secret postgresql-admin"])
        subgraph bases[" "]
            direction TB
            dbtroc[("base troc")]
            dbnotif[("base notification")]
            dbkc[("base keycloak")]
        end
        rtroc(["rôle troc<br/>Secret troc-db"])
        rnotif(["rôle notification<br/>Secret notification-db"])
        rkc(["rôle keycloak<br/>Secret keycloak-db"])
    end

    troc["troc-service"] --> rtroc
    notif["notification-service"] --> rnotif
    kc["Keycloak (SQ-06)"] --> rkc

    rtroc -- "propriétaire" --> dbtroc
    rnotif -- "propriétaire" --> dbnotif
    rkc -- "propriétaire" --> dbkc
    rtroc -. "CONNECT refusé" .-x dbnotif
    rnotif -. "CONNECT refusé" .-x dbtroc
    admin -- "initialisation seulement<br/>(création des bases et des rôles)" --> bases
```

- Le rôle `postgres` sert uniquement au script d'initialisation ; aucun service ne l'utilise.
- Chaque service applique ses migrations Flyway avec son propre rôle, propriétaire de sa base (ADR 0013).
- Aucune clé étrangère entre bases : les services ne partagent pas de données (`docs/cadrage.md` §1.2 point 7).

## 2. Modèle de données cible

> **Modèle cible** : les tables arrivent avec les user stories. `annonce` est implémentée depuis US1a (migration
> `V2__annonce.sql`, accès par `JdbcClient`, ADR 0019) ; `abonnement` depuis US2 (migration `V2__abonnement.sql` de
> `notification-service`, catégorie stockée par son code, index par catégorie pour US3) ; `notification` arrive avec US3.
> Noms de colonnes et types indicatifs ; les règles viennent du cadrage et du backlog.

```mermaid
erDiagram
    ANNONCE {
        uuid id PK
        varchar titre "3 à 80 caractères"
        varchar description "1000 caractères max"
        varchar categorie "liste fermée (5 catégories)"
        varchar type "ECHANGE ou DON"
        varchar statut "PUBLIEE ou RETIREE"
        varchar auteur_id "sub du jeton"
        varchar photo_public_id "Cloudinary"
        varchar photo_url
        timestamptz cree_le
    }
    ABONNEMENT {
        varchar membre_id UK "sub du jeton"
        varchar categorie UK "liste fermée (5 catégories)"
    }
    NOTIFICATION {
        uuid id PK
        varchar membre_id UK "destinataire (sub)"
        uuid annonce_id UK "id de l'annonce (événement)"
        varchar type UK "CONFIRMATION ou ALERTE"
        varchar titre_annonce "copié de l'événement"
        boolean lue "false à la création"
        timestamptz cree_le
    }
    ANNONCE ||..o{ NOTIFICATION : "AnnoncePubliee (référence logique, autre base)"
```

| Table | Base | Contrainte d'unicité | Raison |
|---|---|---|---|
| `annonce` | `troc` | clé primaire `id` | — |
| `abonnement` | `notification` | `(membre_id, categorie)` | `PUT /abonnements/{categorie}` idempotent, pas de doublon (US2) |
| `notification` | `notification` | `(membre_id, annonce_id, type)` | idempotence du consommateur : un message livré deux fois ne crée qu'une notification (`docs/cadrage.md` §1.2 point 8, US3) |

- `annonce` est dans la base `troc` ; `abonnement` et `notification` sont dans la base `notification`
  (`notification-service` détient les abonnements, `docs/cadrage.md` §1.2 point 7).
- Les membres ne sont pas stockés : ils sont identifiés par le `sub` du jeton Keycloak (`docs/cadrage.md` §1.1 point 2).
- `annonce_id` n'est pas une clé étrangère : l'annonce est dans une autre base, l'identifiant arrive par l'événement
  `AnnoncePubliee`.
