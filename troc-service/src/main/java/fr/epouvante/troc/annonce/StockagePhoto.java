package fr.epouvante.troc.annonce;

import java.util.Arrays;
import java.util.Objects;

// Stockage des photos d'annonces (docs/cadrage.md §1.1 point 3). Ne reçoit que des photos déjà vérifiées
// (taille, format, premiers octets) : aucune photo invalide n'est envoyée à l'hébergeur.
interface StockagePhoto {

    PhotoStockee stocker(PhotoVerifiee photo);

    void supprimer(String publicId);

    // Photo dont la taille et le format ont été vérifiés par VerificationPhoto. Égalité sur le contenu du tableau ;
    // toString sans les octets (une photo de 2 Mo dans un log).
    record PhotoVerifiee(byte[] octets, Format format) {

        @Override
        public boolean equals(Object autre) {
            return autre instanceof PhotoVerifiee p && format == p.format && Arrays.equals(octets, p.octets);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(octets) + Objects.hashCode(format);
        }

        @Override
        public String toString() {
            return "PhotoVerifiee[format=" + format + ", taille=" + octets.length + " octets]";
        }
    }

    // Formats autorisés (docs/cadrage.md §1.1 point 3).
    enum Format {
        JPEG,
        PNG,
        WEBP
    }

    // Identifiant chez l'hébergeur (pour supprimer la photo) et URL publique (pour l'afficher).
    record PhotoStockee(String publicId, String url) {
    }

    // Hébergeur en erreur, injoignable ou trop lent. Le message ne contient que le code HTTP ou le type d'erreur,
    // jamais la réponse de l'hébergeur (Cloudinary y renvoie la clé envoyée, exp. 4 §7) : il peut être journalisé.
    class StockageIndisponible extends RuntimeException {
        StockageIndisponible(String message) {
            super(message);
        }
    }
}
