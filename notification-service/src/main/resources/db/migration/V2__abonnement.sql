-- US2 : catégories d'intérêt des membres (docs/schemas/base-de-donnees.md ; docs/cadrage.md §1.1 point 1, §1.2 point 7).
-- La clé primaire rend l'ajout idempotent : PUT /abonnements/{categorie} rejoué ne crée pas de doublon.
create table abonnement (
    -- sub du jeton Keycloak, jamais lu dans la requête.
    membre_id varchar(64) not null,
    categorie varchar(20) not null check (categorie in ('FIGURINES', 'FILMS', 'BD', 'JEUX_DE_SOCIETE', 'GOODIES')),
    primary key (membre_id, categorie)
);

-- US3 : membres abonnés à la catégorie d'une annonce publiée.
create index abonnement_par_categorie on abonnement (categorie);
