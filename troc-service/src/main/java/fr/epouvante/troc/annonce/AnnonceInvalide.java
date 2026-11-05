package fr.epouvante.troc.annonce;

import java.util.Map;

// Annonce refusée (400) : un message par champ, affiché sous le champ par l'écran « Publier une annonce ».
class AnnonceInvalide extends RuntimeException {

    private final transient Map<String, String> erreurs;

    AnnonceInvalide(Map<String, String> erreurs) {
        super("Annonce invalide : " + erreurs.keySet());
        this.erreurs = Map.copyOf(erreurs);
    }

    Map<String, String> erreurs() {
        return erreurs;
    }
}
