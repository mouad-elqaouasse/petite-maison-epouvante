package fr.epouvante.troc.annonce;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import fr.epouvante.troc.annonce.StockagePhoto.Format;
import fr.epouvante.troc.annonce.StockagePhoto.PhotoStockee;
import fr.epouvante.troc.annonce.StockagePhoto.PhotoVerifiee;

// Adaptateur Cloudinary de StockagePhoto, testé seul contre une API Cloudinary simulée par WireMock
// (docs/cadrage.md §1.3.12 ; réponses relevées à l'expérience 4, docs/experimentations/exp4-cloudinary-rapport.md §5).
// Scénarios repris de l'ancienne US1b (fusionnée dans US1a le 05/11/2026) : même nom (@DisplayName).
@ExtendWith(OutputCaptureExtension.class)
class StockagePhotoCloudinaryTest {

    @RegisterExtension
    static final WireMockExtension CLOUDINARY = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String CLOUD = "nuage-test";
    private static final String CLE = "cle-de-test-123";
    private static final String SECRET = "secret-de-test-456";
    private static final long MAINTENANT = 1_793_000_000L;
    private static final String UPLOAD = "/v1_1/" + CLOUD + "/image/upload";
    private static final String DESTROY = "/v1_1/" + CLOUD + "/image/destroy";
    // Message réel de Cloudinary pour une clé inconnue : il renvoie la clé envoyée (exp. 4, §7).
    private static final String MESSAGE_CLOUDINARY = "Invalid api_key " + CLE;

    private final byte[] octets = Photos.jpeg(150 * Photos.KO);

    private StockagePhotoCloudinary adaptateur(Duration delaiLecture) {
        CloudinaryProprietes proprietes = new CloudinaryProprietes(CLOUDINARY.baseUrl(), CLOUD, CLE, SECRET,
                "epouvante", Duration.ofSeconds(2), delaiLecture);
        return new StockagePhotoCloudinary(proprietes, Clock.fixed(Instant.ofEpochSecond(MAINTENANT), ZoneOffset.UTC));
    }

    private StockagePhotoCloudinary adaptateur() {
        return adaptateur(Duration.ofSeconds(10));
    }

    private static String sha1(String texte) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(texte.getBytes(StandardCharsets.UTF_8)));
    }

    private static void reponseUpload(int statut, String corps) {
        CLOUDINARY.stubFor(post(UPLOAD).willReturn(aResponse().withStatus(statut)
                .withHeader("Content-Type", "application/json").withBody(corps)));
    }

    @Test
    void la_photo_d_une_annonce_valide_est_stockee_sur_cloudinary() {
        CLOUDINARY.stubFor(post(UPLOAD).willReturn(okJson("""
                {"public_id": "epouvante/abc123", "secure_url": "https://res.cloudinary.com/nuage-test/image/upload/v1/epouvante/abc123.jpg",
                 "url": "http://res.cloudinary.com/nuage-test/image/upload/v1/epouvante/abc123.jpg"}
                """)));

        PhotoStockee photo = adaptateur().stocker(new PhotoVerifiee(octets, Format.JPEG));

        assertThat(photo.publicId()).isEqualTo("epouvante/abc123");
        assertThat(photo.url()).isEqualTo("https://res.cloudinary.com/nuage-test/image/upload/v1/epouvante/abc123.jpg");
    }

    @Test
    void la_photo_est_envoyee_telle_quelle() {
        reponseUpload(200, "{\"public_id\": \"epouvante/abc\", \"secure_url\": \"https://res.cloudinary.com/x.jpg\"}");

        adaptateur().stocker(new PhotoVerifiee(octets, Format.JPEG));

        CLOUDINARY.verify(postRequestedFor(urlEqualTo(UPLOAD))
                .withRequestBodyPart(aMultipart("file").withBody(binaryEqualTo(octets)).build()));
    }

    // « La clé Cloudinary est lue depuis le secret » : clé et signature calculées avec les valeurs configurées
    // (variables d'environnement issues du Secret Kubernetes cloudinary, deploy/scripts/cloudinary-secrets.sh).
    @Test
    void l_envoi_est_signe_avec_la_cle_et_le_secret_configures() throws Exception {
        reponseUpload(200, "{\"public_id\": \"epouvante/abc\", \"secure_url\": \"https://res.cloudinary.com/x.jpg\"}");

        adaptateur().stocker(new PhotoVerifiee(octets, Format.JPEG));

        String attendue = sha1("asset_folder=epouvante&timestamp=" + MAINTENANT + SECRET);
        CLOUDINARY.verify(postRequestedFor(urlEqualTo(UPLOAD))
                .withRequestBodyPart(aMultipart("api_key").withBody(equalTo(CLE)).build())
                .withRequestBodyPart(aMultipart("timestamp").withBody(equalTo(String.valueOf(MAINTENANT))).build())
                .withRequestBodyPart(aMultipart("asset_folder").withBody(equalTo("epouvante")).build())
                .withRequestBodyPart(aMultipart("signature").withBody(equalTo(attendue)).build()));
    }

    @Test
    void une_erreur_de_cloudinary_fait_echouer_le_stockage() {
        reponseUpload(500, "{\"error\": {\"message\": \"Internal error\"}}");

        assertThatThrownBy(() -> adaptateur().stocker(new PhotoVerifiee(octets, Format.JPEG)))
                .isInstanceOf(StockagePhoto.StockageIndisponible.class);
    }

    @Test
    void un_cloudinary_trop_lent_fait_echouer_le_stockage_des_la_fin_du_delai() {
        CLOUDINARY.stubFor(post(UPLOAD).willReturn(okJson("{}").withFixedDelay(5_000)));

        long debut = System.nanoTime();
        assertThatThrownBy(() -> adaptateur(Duration.ofMillis(500)).stocker(new PhotoVerifiee(octets, Format.JPEG)))
                .isInstanceOf(StockagePhoto.StockageIndisponible.class);

        assertThat(Duration.ofNanos(System.nanoTime() - debut)).isLessThan(Duration.ofSeconds(3));
    }

    // Réponses observées à l'expérience 4 : 401 « Invalid Signature », 401 « Invalid api_key <clé> »,
    // 400 « File size too large ». Ni le message ni la clé ne sortent de l'adaptateur (exception, logs).
    @Test
    void les_refus_de_cloudinary_ne_laissent_passer_ni_son_message_ni_la_cle(CapturedOutput sortie) {
        for (String corps : new String[] {
                "{\"error\": {\"message\": \"" + MESSAGE_CLOUDINARY + "\"}}",
                "{\"error\": {\"message\": \"Invalid Signature 0123. String to sign - 'asset_folder=epouvante'.\"}}"}) {
            reponseUpload(401, corps);
            assertThatThrownBy(() -> adaptateur().stocker(new PhotoVerifiee(octets, Format.JPEG)))
                    .isInstanceOf(StockagePhoto.StockageIndisponible.class)
                    .hasMessageContaining("401")
                    .satisfies(e -> assertThat(e.toString()).doesNotContain(CLE, "Invalid", "String to sign"));
        }
        reponseUpload(400, "{\"error\": {\"message\": \"File size too large. Got 12582966.\"}}");
        assertThatThrownBy(() -> adaptateur().stocker(new PhotoVerifiee(octets, Format.JPEG)))
                .isInstanceOf(StockagePhoto.StockageIndisponible.class)
                .hasMessageContaining("400");

        assertThat(sortie.getAll()).doesNotContain(CLE, SECRET, "Invalid", "File size too large");
    }

    @Test
    void la_suppression_demande_destroy_avec_invalidation_et_signature() throws Exception {
        CLOUDINARY.stubFor(post(DESTROY).willReturn(okJson("{\"result\": \"ok\"}")));

        adaptateur().supprimer("epouvante/abc123");

        String attendue = sha1("invalidate=true&public_id=epouvante/abc123&timestamp=" + MAINTENANT + SECRET);
        CLOUDINARY.verify(postRequestedFor(urlEqualTo(DESTROY))
                .withFormParam("public_id", equalTo("epouvante/abc123"))
                .withFormParam("invalidate", equalTo("true"))
                .withFormParam("api_key", equalTo(CLE))
                .withFormParam("timestamp", equalTo(String.valueOf(MAINTENANT)))
                .withFormParam("signature", equalTo(attendue)));
    }

    @Test
    void une_suppression_refusee_fait_echouer_sans_message_de_cloudinary(CapturedOutput sortie) {
        CLOUDINARY.stubFor(post(DESTROY).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\": {\"message\": \"" + MESSAGE_CLOUDINARY + "\"}}")));

        assertThatThrownBy(() -> adaptateur().supprimer("epouvante/abc123"))
                .isInstanceOf(StockagePhoto.StockageIndisponible.class)
                .satisfies(e -> assertThat(e.toString()).doesNotContain(CLE, "Invalid"));
        assertThat(sortie.getAll()).doesNotContain(CLE, "Invalid");
    }

    @Test
    void une_configuration_incomplete_est_refusee_sans_afficher_les_valeurs() {
        CloudinaryProprietes incomplete = new CloudinaryProprietes(CLOUDINARY.baseUrl(), CLOUD, "", SECRET,
                "epouvante", Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThatThrownBy(() -> new StockagePhotoCloudinary(incomplete, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET, CLOUD));
    }
}
