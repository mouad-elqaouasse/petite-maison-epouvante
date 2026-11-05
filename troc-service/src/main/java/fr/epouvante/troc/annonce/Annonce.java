package fr.epouvante.troc.annonce;

import java.time.Instant;
import java.util.UUID;

// Annonce de troc (docs/cadrage.md §1.1 points 2, 3 et 5).
record Annonce(
        UUID id,
        String titre,
        String description,
        Categorie categorie,
        Type type,
        Statut statut,
        String auteurId,
        String photoPublicId,
        String photoUrl,
        Instant creeLe) {

    // Échange ou don (docs/cadrage.md §1.1 point 2).
    enum Type {
        ECHANGE,
        DON
    }

    // Modération a posteriori : publiée immédiatement, retirée par un modérateur (US5).
    enum Statut {
        PUBLIEE,
        RETIREE
    }
}
