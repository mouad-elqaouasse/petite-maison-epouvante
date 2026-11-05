package fr.epouvante.troc.annonce;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.response.ValidatableResponse;

// Publication avec l'implémentation Cloudinary active (aucun profil), face à une API Cloudinary simulée par WireMock.
// Scénarios repris de l'ancienne US1b (fusionnée dans US1a le 05/11/2026), même nom (@DisplayName).
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            // Aucun profil : la configuration de production (le profil photo-factice des tests est retiré).
            "spring.profiles.active=",
            "app.cloudinary.cloud-name=nuage-test",
            "app.cloudinary.api-key=cle-de-test-123",
            "app.cloudinary.api-secret=secret-de-test-456",
            "app.cloudinary.delai-lecture=1s"
        })
class CloudinaryAcceptationTest {

    @RegisterExtension
    static final WireMockExtension CLOUDINARY = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String UPLOAD = "/v1_1/nuage-test/image/upload";
    private static final String MESSAGE_CLOUDINARY = "Invalid api_key cle-de-test-123";

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry proprietes) {
        KeycloakPartage.proprietes(proprietes);
        proprietes.add("app.cloudinary.url", CLOUDINARY::baseUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient base;

    @Autowired
    ApplicationContext contexte;

    private String alice;

    @BeforeEach
    void contexte() {
        base.sql("delete from annonce").update();
        alice = KeycloakPartage.jeton("alice");
    }

    private ValidatableResponse publier(byte[] photo, String nom, String type) {
        return given().baseUri("http://localhost:" + port).basePath("/api/troc")
                .auth().oauth2(alice)
                .multiPart(new MultiPartSpecBuilder("Masque de Jason").controlName("titre").emptyFileName().build())
                .multiPart(new MultiPartSpecBuilder("Films").controlName("categorie").emptyFileName().build())
                .multiPart(new MultiPartSpecBuilder("DON").controlName("type").emptyFileName().build())
                .multiPart("photo", nom, photo, type)
                .post("/annonces")
                .then().log().ifValidationFails();
    }

    private int annoncesEnregistrees() {
        return base.sql("select count(*) from annonce").query(Integer.class).single();
    }

    @Test
    @DisplayName("La photo d'une annonce valide est stockée sur Cloudinary")
    void la_photo_d_une_annonce_valide_est_stockee_sur_cloudinary() {
        CLOUDINARY.stubFor(post(UPLOAD).willReturn(okJson("""
                {"public_id": "epouvante/jason", "secure_url": "https://res.cloudinary.com/nuage-test/image/upload/epouvante/jason.jpg"}
                """)));

        publier(Photos.jpeg(150 * Photos.KO), "jason.jpg", "image/jpeg")
                .statusCode(201)
                .body("photoUrl", equalTo("https://res.cloudinary.com/nuage-test/image/upload/epouvante/jason.jpg"));

        assertThat(base.sql("select photo_public_id from annonce").query(String.class).single())
                .isEqualTo("epouvante/jason");
    }

    @Test
    @DisplayName("Une photo invalide n'est jamais envoyée à Cloudinary")
    void une_photo_invalide_n_est_jamais_envoyee_a_cloudinary() {
        publier(Photos.gif(10 * Photos.KO), "chauve-souris.gif", "image/gif").statusCode(400);

        CLOUDINARY.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Une erreur de Cloudinary fait échouer la publication")
    void une_erreur_de_cloudinary_fait_echouer_la_publication(CapturedOutput sortie) {
        CLOUDINARY.stubFor(post(UPLOAD).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\": {\"message\": \"" + MESSAGE_CLOUDINARY + "\"}}")));

        publier(Photos.jpeg(150 * Photos.KO), "jason.jpg", "image/jpeg")
                .statusCode(greaterThanOrEqualTo(500))
                .body(not(containsString("Invalid")))
                .body(not(containsString("cle-de-test-123")));

        assertThat(annoncesEnregistrees()).isZero();
        assertThat(sortie.getAll()).doesNotContain(MESSAGE_CLOUDINARY, "cle-de-test-123");
    }

    @Test
    @DisplayName("Un Cloudinary trop lent fait échouer la publication sans bloquer le service")
    void un_cloudinary_trop_lent_fait_echouer_la_publication_sans_bloquer_le_service() {
        CLOUDINARY.stubFor(post(UPLOAD).willReturn(okJson("{}").withFixedDelay(10_000)));

        long debut = System.nanoTime();
        publier(Photos.jpeg(150 * Photos.KO), "jason.jpg", "image/jpeg").statusCode(greaterThanOrEqualTo(500));

        assertThat(Duration.ofNanos(System.nanoTime() - debut)).isLessThan(Duration.ofSeconds(5));
        assertThat(annoncesEnregistrees()).isZero();
    }

    @Test
    @DisplayName("Le mode factice est désactivé par défaut")
    void le_mode_factice_est_desactive_par_defaut() {
        assertThat(contexte.getBean(Environment.class).getActiveProfiles()).isEmpty();
        assertThat(contexte.getBean(StockagePhoto.class)).isInstanceOf(StockagePhotoCloudinary.class);
        assertThat(contexte.getBeansOfType(StockagePhotoFactice.class)).isEmpty();
    }
}
