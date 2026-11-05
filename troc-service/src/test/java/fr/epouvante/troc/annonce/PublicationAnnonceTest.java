package fr.epouvante.troc.annonce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;

import fr.epouvante.troc.annonce.StockagePhoto.Format;
import fr.epouvante.troc.annonce.StockagePhoto.PhotoStockee;
import fr.epouvante.troc.annonce.StockagePhoto.PhotoVerifiee;

// Pas de photo orpheline : si l'enregistrement échoue, la photo déjà stockée est supprimée (docs/cadrage.md §1.1
// point 3 ; scénario repris de l'ancienne US1b, fusionnée dans US1a le 05/11/2026).
class PublicationAnnonceTest {

    private final List<String> supprimees = new ArrayList<>();

    private final StockagePhoto stockage = new StockagePhoto() {
        @Override
        public PhotoStockee stocker(PhotoVerifiee photo) {
            return new PhotoStockee("epouvante/abc123", "https://res.cloudinary.com/x/abc123.jpg");
        }

        @Override
        public void supprimer(String publicId) {
            supprimees.add(publicId);
        }
    };

    private final AnnonceDepot depotEnPanne = new AnnonceDepot(null) {
        @Override
        void enregistrer(Annonce annonce) {
            throw new DataAccessResourceFailureException("base indisponible");
        }
    };

    private final FormulaireAnnonce.Demande demande = new FormulaireAnnonce.Demande("Masque de Jason", null,
            Categorie.FILMS, Annonce.Type.DON, new PhotoVerifiee(Photos.jpeg(1024), Format.JPEG));

    @Test
    void la_photo_est_supprimee_si_l_enregistrement_de_l_annonce_echoue() {
        PublicationAnnonce publication = new PublicationAnnonce(stockage, depotEnPanne, evenement -> { },
                TransactionOperations.withoutTransaction());

        assertThatThrownBy(() -> publication.publier(demande, "sub-alice"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(supprimees).containsExactly("epouvante/abc123");
    }

    @Test
    void un_echec_de_la_suppression_ne_masque_pas_l_erreur_d_enregistrement() {
        StockagePhoto suppressionEnPanne = new StockagePhoto() {
            @Override
            public PhotoStockee stocker(PhotoVerifiee photo) {
                return new PhotoStockee("epouvante/abc123", "https://res.cloudinary.com/x/abc123.jpg");
            }

            @Override
            public void supprimer(String publicId) {
                throw new StockageIndisponible("Cloudinary a répondu 500");
            }
        };
        PublicationAnnonce publication = new PublicationAnnonce(suppressionEnPanne, depotEnPanne, evenement -> { },
                TransactionOperations.withoutTransaction());

        assertThatThrownBy(() -> publication.publier(demande, "sub-alice"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
    }
}
