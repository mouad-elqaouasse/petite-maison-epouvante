package fr.epouvante.troc.annonce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// SQ-14, décision D4 : la base des URL factices est réglable. Par défaut, un domaine réservé qui ne mène nulle part ;
// l'E2E la règle sur res.cloudinary.com, seul hôte d'images autorisé par la CSP du front, et sert ces URL par une
// route Playwright (aucun appel au vrai Cloudinary).
class StockagePhotoFacticeTest {

    // Le bean n'existe qu'avec le profil photo-factice.
    private final ApplicationContextRunner contexte = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=photo-factice")
            .withBean(StockagePhotoFactice.class);

    private static StockagePhoto.PhotoStockee stocker(StockagePhotoFactice stockage) {
        return stockage.stocker(null);
    }

    @Test
    void par_defaut_l_url_mene_a_un_domaine_reserve() {
        contexte.run(c -> assertThat(stocker(c.getBean(StockagePhotoFactice.class)).url())
                .startsWith("https://photos.invalid/factice/"));
    }

    @Test
    void la_base_des_url_est_reglable() {
        contexte.withPropertyValues("app.photo-factice.url-base=https://res.cloudinary.com/e2e/image/upload/")
                .run(c -> {
                    StockagePhoto.PhotoStockee photo = stocker(c.getBean(StockagePhotoFactice.class));
                    assertThat(photo.url()).isEqualTo("https://res.cloudinary.com/e2e/image/upload/" + photo.publicId());
                });
    }
}
