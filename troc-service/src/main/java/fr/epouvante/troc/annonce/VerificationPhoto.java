package fr.epouvante.troc.annonce;

import org.springframework.stereotype.Component;

import fr.epouvante.troc.annonce.StockagePhoto.Format;
import fr.epouvante.troc.annonce.StockagePhoto.PhotoVerifiee;

// Vérifie une photo avant tout stockage (docs/cadrage.md §1.1 point 3) : 2 Mo au plus, format lu dans les premiers
// octets (magic bytes), jamais dans le nom du fichier ni le type déclaré par le client. Cloudinary accepte 10 Mio et
// le BMP : ce contrôle est la seule barrière (docs/experimentations/exp4-cloudinary-rapport.md §9).
@Component
class VerificationPhoto {

    static final int TAILLE_MAX = 2 * 1024 * 1024;

    // Motif lisible par le membre, renvoyé dans la réponse 400 sous le champ photo.
    static class PhotoRefusee extends RuntimeException {
        PhotoRefusee(String motif) {
            super(motif);
        }
    }

    PhotoVerifiee verifier(byte[] octets) {
        if (octets.length > TAILLE_MAX) {
            throw new PhotoRefusee("La photo dépasse 2 Mo : choisissez un fichier plus léger.");
        }
        Format format = format(octets);
        if (format == null) {
            throw new PhotoRefusee("Le fichier n’est pas une image JPEG, PNG ou WebP.");
        }
        return new PhotoVerifiee(octets, format);
    }

    private static Format format(byte[] o) {
        if (commencePar(o, 0, 0xFF, 0xD8, 0xFF)) {
            return Format.JPEG;
        }
        if (commencePar(o, 0, 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n')) {
            return Format.PNG;
        }
        if (commencePar(o, 0, 'R', 'I', 'F', 'F') && commencePar(o, 8, 'W', 'E', 'B', 'P')) {
            return Format.WEBP;
        }
        return null;
    }

    private static boolean commencePar(byte[] octets, int debut, int... signature) {
        if (octets.length < debut + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((octets[debut + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
