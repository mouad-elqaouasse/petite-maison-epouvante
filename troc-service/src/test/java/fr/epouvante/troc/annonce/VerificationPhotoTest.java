package fr.epouvante.troc.annonce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import fr.epouvante.troc.annonce.StockagePhoto.Format;

// Vérification d'une photo avant tout stockage : taille et format lu dans les premiers octets
// (docs/cadrage.md §1.1 point 3 ; docs/experimentations/exp4-cloudinary-rapport.md §9).
class VerificationPhotoTest {

    private final VerificationPhoto verification = new VerificationPhoto();

    @Test
    void un_jpeg_est_reconnu() {
        assertThat(verification.verifier(Photos.jpeg(150 * Photos.KO)).format()).isEqualTo(Format.JPEG);
    }

    @Test
    void un_png_est_reconnu() {
        assertThat(verification.verifier(Photos.png(150 * Photos.KO)).format()).isEqualTo(Format.PNG);
    }

    @Test
    void un_webp_est_reconnu() {
        assertThat(verification.verifier(Photos.webp(150 * Photos.KO)).format()).isEqualTo(Format.WEBP);
    }

    @Test
    void une_photo_de_2_mo_exactement_est_acceptee() {
        assertThat(verification.verifier(Photos.jpeg(2 * Photos.MO)).format()).isEqualTo(Format.JPEG);
    }

    @Test
    void une_photo_de_2_mo_plus_1_octet_est_refusee() {
        assertThatThrownBy(() -> verification.verifier(Photos.jpeg(2 * Photos.MO + 1)))
                .isInstanceOf(VerificationPhoto.PhotoRefusee.class)
                .hasMessageContaining("2 Mo");
    }

    @Test
    void un_gif_est_refuse() {
        assertThatThrownBy(() -> verification.verifier(Photos.gif(10 * Photos.KO)))
                .isInstanceOf(VerificationPhoto.PhotoRefusee.class)
                .hasMessageContaining("JPEG, PNG ou WebP");
    }

    @Test
    void un_fichier_texte_est_refuse() {
        assertThatThrownBy(() -> verification.verifier(Photos.texte()))
                .isInstanceOf(VerificationPhoto.PhotoRefusee.class);
    }

    @Test
    void un_fichier_vide_ou_trop_court_est_refuse() {
        assertThatThrownBy(() -> verification.verifier(new byte[0])).isInstanceOf(VerificationPhoto.PhotoRefusee.class);
        assertThatThrownBy(() -> verification.verifier(new byte[] {(byte) 0xFF, (byte) 0xD8}))
                .isInstanceOf(VerificationPhoto.PhotoRefusee.class);
    }

    @Test
    void un_riff_qui_n_est_pas_un_webp_est_refuse() {
        byte[] wav = Photos.webp(1024);
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';

        assertThatThrownBy(() -> verification.verifier(wav)).isInstanceOf(VerificationPhoto.PhotoRefusee.class);
    }

    @Test
    void les_octets_sont_transmis_tels_quels() {
        byte[] octets = Photos.jpeg(150 * Photos.KO);

        assertThat(verification.verifier(octets).octets()).isEqualTo(octets);
    }
}
