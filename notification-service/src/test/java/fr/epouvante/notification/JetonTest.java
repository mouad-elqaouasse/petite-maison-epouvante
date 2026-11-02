package fr.epouvante.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

// Ticket SQ-06 : validation des jetons Keycloak par le service (ADR 0003, ADR 0011).
// Issuer attendu = nom d'hôte du conteneur (adresse « publique ») ; clés lues par une URL fixée explicitement.
// Keycloak en mode dev sans nom d'hôte fixe : l'issuer d'un jeton suit l'adresse utilisée pour l'obtenir,
// un jeton demandé par l'adresse IP du même hôte est donc signé par les mêmes clés mais porte un autre issuer.
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "app.securite.tolerance-horloge=0s"})
class JetonTest {

    // Même version que sur le cluster (ADR 0003).
    @Container
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/realm-test.json"),
                    "/opt/keycloak/data/import/realm-test.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/test").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry proprietes) {
        proprietes.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realm(HOTE));
        proprietes.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realm(HOTE) + "/protocol/openid-connect/certs");
    }

    private static final String HOTE = KEYCLOAK.getHost();

    // Même machine, autre écriture de l'adresse : autre issuer.
    private static String autreHote() throws Exception {
        String ip = InetAddress.getByName(HOTE).getHostAddress();
        assertThat(ip).isNotEqualTo(HOTE);
        return ip;
    }

    @LocalServerPort
    int portApi;

    private static String realm(String hote) {
        return "http://" + hote + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/test";
    }

    private static String jeton(String hote, String client, String utilisateur) throws Exception {
        String formulaire = Map.of("grant_type", "password", "client_id", client,
                        "username", utilisateur, "password", utilisateur)
                .entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest requete = HttpRequest.newBuilder(URI.create(realm(hote) + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formulaire))
                .build();
        HttpResponse<String> reponse = HttpClient.newHttpClient().send(requete, HttpResponse.BodyHandlers.ofString());
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        Matcher m = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(reponse.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> moi(String jeton) throws Exception {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://localhost:" + portApi + "/api/notifications/moi"));
        if (jeton != null) {
            requete.header("Authorization", "Bearer " + jeton);
        }
        return HttpClient.newHttpClient().send(requete.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void moi_avec_un_jeton_valide_renvoie_l_utilisateur_et_ses_roles() throws Exception {
        HttpResponse<String> reponse = moi(jeton(HOTE, "client-test", "moderateur-test"));

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body())
                .contains("\"sub\":\"")
                .contains("\"nom\":\"moderateur-test\"")
                .contains("\"roles\":[\"MODERATEUR\",\"USER\"]");
    }

    @Test
    void moi_d_un_membre_ne_contient_que_le_role_user() throws Exception {
        HttpResponse<String> reponse = moi(jeton(HOTE, "client-test", "membre-test"));

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body()).contains("\"roles\":[\"USER\"]");
    }

    @Test
    void moi_sans_jeton_401() throws Exception {
        assertThat(moi(null).statusCode()).isEqualTo(401);
    }

    @Test
    void moi_avec_un_jeton_d_un_autre_issuer_401() throws Exception {
        assertThat(moi(jeton(autreHote(), "client-test", "membre-test")).statusCode()).isEqualTo(401);
    }

    @Test
    void moi_avec_un_jeton_sans_l_audience_du_service_401() throws Exception {
        assertThat(moi(jeton(HOTE, "client-sans-audience", "membre-test")).statusCode()).isEqualTo(401);
    }

    @Test
    void moi_avec_un_jeton_expire_401() throws Exception {
        String jeton = jeton(HOTE, "client-jeton-court", "membre-test");
        Thread.sleep(2_000);

        assertThat(moi(jeton).statusCode()).isEqualTo(401);
    }

    // Méthode non prise en charge (405), pas un refus CSRF (403) : la protection CSRF ne gêne pas les appels
    // authentifiés par jeton Bearer.
    @Test
    void post_avec_un_jeton_n_est_pas_bloque_par_la_protection_csrf() throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + portApi + "/api/notifications/moi"))
                .header("Authorization", "Bearer " + jeton(HOTE, "client-test", "membre-test"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        assertThat(HttpClient.newHttpClient().send(requete, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(405);
    }

    @Test
    void version_reste_publique() throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(
                URI.create("http://localhost:" + portApi + "/api/notifications/version")).build();

        assertThat(HttpClient.newHttpClient().send(requete, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);
    }
}
