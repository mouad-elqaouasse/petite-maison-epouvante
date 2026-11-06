package fr.epouvante.notification.notifier;

import java.time.Instant;
import java.util.UUID;

import fr.epouvante.notification.abonnement.Categorie;

// Événement AnnoncePubliee, version 1, vu du consommateur (contrat : docs/schemas/evenement-annonce-publiee.md,
// ADR 0020). Aucune classe partagée avec troc-service : seul le JSON fait foi.
record AnnoncePubliee(
        Integer version,
        UUID annonceId,
        String categorie,
        String auteurId,
        String titre,
        Instant creeLe,
        String photoUrl) {

    static final int VERSION_CONTRAT = 1;

    // Vérifie le contrat ; un événement refusé part en DLQ après 4 livraisons (ADR 0025).
    Categorie verifier() {
        if (version == null || version != VERSION_CONTRAT) {
            throw new EvenementRefuse("version inconnue : " + version);
        }
        if (annonceId == null || auteurId == null || auteurId.isBlank() || titre == null || titre.isBlank()) {
            throw new EvenementRefuse("champ obligatoire manquant");
        }
        return Categorie.depuisLibelle(categorie)
                .orElseThrow(() -> new EvenementRefuse("catégorie inconnue : " + categorie));
    }

    static final class EvenementRefuse extends RuntimeException {
        EvenementRefuse(String motif) {
            super(motif, null, false, false);
        }
    }
}
