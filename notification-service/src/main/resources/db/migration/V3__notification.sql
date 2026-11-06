-- US3 : notifications créées à la réception de AnnoncePubliee (docs/schemas/base-de-donnees.md ; docs/cadrage.md §1.2 point 8).
-- L'unicité (membre_id, annonce_id, type) rend le consommateur idempotent : un événement livré deux fois ne crée rien de plus.
-- La colonne lue arrive avec US4 (ADR 0025, D7).
create table notification (
    id uuid primary key default gen_random_uuid(),
    -- sub du jeton Keycloak du destinataire.
    membre_id varchar(64) not null,
    -- Identifiant de l'annonce, dans la base troc : pas de clé étrangère (docs/cadrage.md §1.2 point 7).
    annonce_id uuid not null,
    type varchar(12) not null check (type in ('CONFIRMATION', 'ALERTE')),
    titre_annonce varchar(80) not null,
    cree_le timestamptz not null default now(),
    constraint notification_unique unique (membre_id, annonce_id, type)
);
