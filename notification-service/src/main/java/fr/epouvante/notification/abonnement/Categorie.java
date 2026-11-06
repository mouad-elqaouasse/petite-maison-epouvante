package fr.epouvante.notification.abonnement;

import java.util.Arrays;
import java.util.Optional;

// Liste fermée des centres d'intérêt (docs/cadrage.md §1.1 point 1 ; contexte.md §1), copiée de troc-service
// (ADR 0024) : aucun appel à troc-service, la gestion des abonnements et les notifications marchent sans lui.
// Le libellé est la valeur échangée avec le front et dans AnnoncePubliee ; CategorieTest fige la liste.
enum Categorie {
    FIGURINES("Figurines"),
    FILMS("Films"),
    BD("BD"),
    JEUX_DE_SOCIETE("Jeux de société"),
    GOODIES("Goodies");

    private final String libelle;

    Categorie(String libelle) {
        this.libelle = libelle;
    }

    String libelle() {
        return libelle;
    }

    static Optional<Categorie> depuisLibelle(String libelle) {
        return Arrays.stream(values()).filter(c -> c.libelle.equals(libelle)).findFirst();
    }
}
