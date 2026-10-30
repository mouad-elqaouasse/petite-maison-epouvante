package fr.epouvante.troc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// Ticket SQ-01 : /api/troc/version sur le port de l'API, actuator sur un port de gestion distinct (ADR 0007).
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.version=0123abc", "management.server.port=0"})
class VersionTest {

    @LocalServerPort
    int portApi;

    @Value("${local.management.port}")
    int portGestion;

    private static HttpResponse<String> get(int port, String chemin) throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin)).build();
        return HttpClient.newHttpClient().send(requete, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void version_renvoie_le_nom_du_service_et_le_sha_configure() throws Exception {
        HttpResponse<String> reponse = get(portApi, "/api/troc/version");

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body())
                .contains("\"service\":\"troc-service\"")
                .contains("\"version\":\"0123abc\"");
    }

    @Test
    void actuator_absent_du_port_de_l_api() throws Exception {
        assertThat(get(portApi, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(portApi, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void sondes_et_metriques_sur_le_port_de_gestion() throws Exception {
        assertThat(portGestion).isNotEqualTo(portApi);
        assertThat(get(portGestion, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(portGestion, "/actuator/health/readiness").statusCode()).isEqualTo(200);

        HttpResponse<String> metriques = get(portGestion, "/actuator/prometheus");
        assertThat(metriques.statusCode()).isEqualTo(200);
        assertThat(metriques.body()).contains("jvm_memory_used_bytes");
    }
}
