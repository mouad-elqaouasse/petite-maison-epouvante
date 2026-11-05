package fr.epouvante.troc.annonce;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Fichiers de test construits en mémoire : seuls les premiers octets (signature du format) comptent pour le service.
final class Photos {

    static final int KO = 1024;
    static final int MO = 1024 * KO;

    private Photos() {
    }

    static byte[] jpeg(int taille) {
        return avecEntete(taille, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);
    }

    static byte[] png(int taille) {
        return avecEntete(taille, (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n');
    }

    static byte[] webp(int taille) {
        return avecEntete(taille, 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
    }

    static byte[] gif(int taille) {
        return avecEntete(taille, 'G', 'I', 'F', '8', '9', 'a');
    }

    static byte[] texte() {
        return "Ceci n'est pas une image.".repeat(100).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] avecEntete(int taille, int... entete) {
        byte[] octets = new byte[taille];
        Arrays.fill(octets, (byte) 0x42);
        for (int i = 0; i < entete.length; i++) {
            octets[i] = (byte) entete[i];
        }
        return octets;
    }
}
