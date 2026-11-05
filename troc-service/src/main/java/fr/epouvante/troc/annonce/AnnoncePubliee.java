package fr.epouvante.troc.annonce;

import java.time.Instant;
import java.util.UUID;

// Événement AnnoncePubliee, version 1 (contrat : docs/schemas/evenement-annonce-publiee.md ; docs/cadrage.md §1.2
// point 7). troc-service ne connaît pas les destinataires ; annonceId sert de clé anti-doublon au consommateur (US3).
record AnnoncePubliee(
        int version,
        UUID annonceId,
        String categorie,
        String auteurId,
        String titre,
        Instant creeLe,
        String photoUrl) {

    static final int VERSION_CONTRAT = 1;

    static AnnoncePubliee de(Annonce a) {
        return new AnnoncePubliee(VERSION_CONTRAT, a.id(), a.categorie().libelle(), a.auteurId(), a.titre(), a.creeLe(),
                a.photoUrl());
    }
}
