package fr.epouvante.troc.demo;

/**
 * Code volontairement non testé : PR de démonstration SQ-09, la Quality Gate doit échouer sur la couverture.
 * Branche jetable, jamais fusionnée.
 */
public final class CategorieDemo {

    private CategorieDemo() {
    }

    public static String libelle(int code) {
        if (code < 0) {
            throw new IllegalArgumentException("code négatif : " + code);
        }
        String libelle;
        switch (code) {
            case 0 -> libelle = "livres";
            case 1 -> libelle = "films";
            case 2 -> libelle = "jeux";
            case 3 -> libelle = "affiches";
            case 4 -> libelle = "figurines";
            default -> libelle = "autre";
        }
        return libelle;
    }

    public static boolean estRare(int code, int exemplaires) {
        if (exemplaires <= 0) {
            return false;
        }
        int seuil = code == 3 ? 10 : 50;
        return exemplaires < seuil;
    }

    public static String resume(int code, int exemplaires) {
        String rarete = estRare(code, exemplaires) ? "rare" : "courant";
        return libelle(code) + " (" + rarete + ", " + exemplaires + " ex.)";
    }
}
