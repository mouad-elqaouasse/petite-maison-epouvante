package fr.epouvante.troc.annonce;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import fr.epouvante.troc.annonce.StockagePhoto.PhotoVerifiee;

// Contrôle des champs reçus par POST /annonces (docs/cadrage.md §1.1 points 2 et 3) : toutes les erreurs sont
// relevées ensemble ; la photo n'est vérifiée qu'ici, avant tout appel à StockagePhoto.
@Component
class FormulaireAnnonce {

    record Demande(String titre, String description, Categorie categorie, Annonce.Type type, PhotoVerifiee photo) {
    }

    private final VerificationPhoto verification;

    FormulaireAnnonce(VerificationPhoto verification) {
        this.verification = verification;
    }

    Demande lire(String titre, String description, String categorie, String type, List<MultipartFile> photos) {
        Map<String, String> erreurs = new LinkedHashMap<>();

        String titreNettoye = titre == null ? "" : titre.strip();
        if (titreNettoye.codePointCount(0, titreNettoye.length()) < 3
                || titreNettoye.codePointCount(0, titreNettoye.length()) > 80) {
            erreurs.put("titre", "Le titre doit faire entre 3 et 80 caractères.");
        }

        String descriptionNettoyee = description == null || description.isBlank() ? null : description.strip();
        if (descriptionNettoyee != null && descriptionNettoyee.codePointCount(0, descriptionNettoyee.length()) > 1000) {
            erreurs.put("description", "La description ne doit pas dépasser 1000 caractères.");
        }

        Categorie cat = Categorie.depuisLibelle(categorie).orElse(null);
        if (cat == null) {
            erreurs.put("categorie", "Choisissez une catégorie de la liste.");
        }

        Annonce.Type typeAnnonce = type(type);
        if (typeAnnonce == null) {
            erreurs.put("type", "Choisissez le type : échange ou don.");
        }

        PhotoVerifiee photo = photo(photos, erreurs);

        if (!erreurs.isEmpty()) {
            throw new AnnonceInvalide(erreurs);
        }
        return new Demande(titreNettoye, descriptionNettoyee, cat, typeAnnonce, photo);
    }

    private static Annonce.Type type(String type) {
        for (Annonce.Type t : Annonce.Type.values()) {
            if (t.name().equals(type)) {
                return t;
            }
        }
        return null;
    }

    private PhotoVerifiee photo(List<MultipartFile> photos, Map<String, String> erreurs) {
        List<MultipartFile> recues = photos == null ? List.of() : photos.stream().filter(p -> !p.isEmpty()).toList();
        if (recues.isEmpty()) {
            erreurs.put("photo", "Ajoutez une photo de l’objet.");
            return null;
        }
        if (recues.size() > 1) {
            erreurs.put("photo", "Une seule photo par annonce.");
            return null;
        }
        // Taille contrôlée avant la lecture des octets.
        if (recues.getFirst().getSize() > VerificationPhoto.TAILLE_MAX) {
            erreurs.put("photo", "La photo dépasse 2 Mo : choisissez un fichier plus léger.");
            return null;
        }
        try {
            return verification.verifier(recues.getFirst().getBytes());
        } catch (VerificationPhoto.PhotoRefusee refus) {
            erreurs.put("photo", refus.getMessage());
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
