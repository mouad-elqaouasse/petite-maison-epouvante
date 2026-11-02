package fr.epouvante.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;

// Ticket SQ-05 : connexion à RabbitMQ (ADR 0010), santé rabbit dans readiness (pas dans liveness), comme db (ADR 0013).
// Toxiproxy s'intercale entre le service et RabbitMQ pour couper puis rétablir le broker sans changer de port.
@Testcontainers
class RabbitMqTest {

    private static final Network RESEAU = Network.newNetwork();

    // Même version que sur le cluster (exp. 1 et 2) ; ≥ 4.3 exigé par le cadrage (§1.2.8).
    @Container
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine")
            .withAdminUser("notification")
            .withAdminPassword("notification-test")
            .withNetwork(RESEAU)
            .withNetworkAliases("rabbitmq");

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(RESEAU);

    private static Proxy proxy;

    @BeforeAll
    static void creerProxy() throws Exception {
        ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        proxy = client.createProxy("rabbitmq", "0.0.0.0:8666", "rabbitmq:5672");
    }

    @AfterEach
    void retablirLeBroker() throws Exception {
        proxy.enable();
    }

    private static ConfigurableApplicationContext demarrer() {
        return new SpringApplicationBuilder(NotificationApplication.class).run(
                "--spring.rabbitmq.host=" + TOXIPROXY.getHost(),
                "--spring.rabbitmq.port=" + TOXIPROXY.getMappedPort(8666),
                "--spring.rabbitmq.username=notification",
                "--spring.rabbitmq.password=notification-test",
                "--management.health.rabbit.enabled=true",
                "--server.port=0",
                "--management.server.port=0");
    }

    private static int statut(ConfigurableApplicationContext contexte, String chemin) throws Exception {
        String port = contexte.getEnvironment().getProperty("local.management.port");
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin))
                .timeout(Duration.ofSeconds(10))
                .build();
        return HttpClient.newHttpClient().send(requete, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void sante_rabbit_up() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer()) {
            assertThat(statut(contexte, "/actuator/health/rabbit")).isEqualTo(200);
            assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(200);
        }
    }

    @Test
    void broker_indisponible_au_demarrage_le_service_demarre_puis_se_connecte() throws Exception {
        proxy.disable();
        try (ConfigurableApplicationContext contexte = demarrer()) {
            assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(503);
            assertThat(statut(contexte, "/actuator/health/liveness")).isEqualTo(200);

            proxy.enable();
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(200));
        }
    }

    @Test
    void broker_coupe_en_fonctionnement_readiness_down_sans_toucher_liveness() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer()) {
            assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(200);

            proxy.disable();
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(503));
            assertThat(statut(contexte, "/actuator/health/liveness")).isEqualTo(200);

            proxy.enable();
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(200));
        }
    }
}
