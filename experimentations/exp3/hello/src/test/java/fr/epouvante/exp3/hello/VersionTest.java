package fr.epouvante.exp3.hello;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.sha=0123abc", "app.secret=valeur-de-test-a-ne-jamais-afficher"})
class VersionTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String chemin) throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin)).build();
        return HttpClient.newHttpClient().send(requete, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void version_donne_le_sha_et_la_presence_du_secret_sans_sa_valeur() throws Exception {
        HttpResponse<String> reponse = get("/version");

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body())
                .contains("\"sha\":\"0123abc\"")
                .contains("\"secretPresent\":true")
                .doesNotContain("valeur-de-test-a-ne-jamais-afficher");
    }

    @Test
    void la_page_hello_affiche_le_sha() throws Exception {
        HttpResponse<String> reponse = get("/");

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body()).contains("0123abc").doesNotContain("valeur-de-test-a-ne-jamais-afficher");
    }
}
