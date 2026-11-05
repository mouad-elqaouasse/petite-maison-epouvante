-- US1a : annonces de troc (docs/schemas/base-de-donnees.md §2 ; docs/cadrage.md §1.1 points 2, 3 et 5).
-- Les contrôles reprennent les règles de l'API : une donnée invalide ne peut pas entrer, même par un autre chemin.
create table annonce (
    id              uuid          primary key,
    titre           varchar(80)   not null check (char_length(titre) >= 3),
    description     varchar(1000),
    categorie       varchar(20)   not null check (categorie in ('FIGURINES', 'FILMS', 'BD', 'JEUX_DE_SOCIETE', 'GOODIES')),
    type            varchar(10)   not null check (type in ('ECHANGE', 'DON')),
    statut          varchar(10)   not null check (statut in ('PUBLIEE', 'RETIREE')),
    -- sub du jeton Keycloak, jamais lu dans le corps de la requête.
    auteur_id       varchar(64)   not null,
    photo_public_id varchar(255)  not null,
    photo_url       varchar(1024) not null,
    cree_le         timestamptz   not null
);

-- Liste des annonces publiées, les plus récentes d'abord (GET /annonces).
create index annonce_publiees_par_date on annonce (cree_le desc) where statut = 'PUBLIEE';
