package fr.epouvante.troc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;

// Ticket SQ-04 : base troc, santé db dans readiness (pas dans liveness), Flyway au démarrage avec réessais (ADR 0013).
// Toxiproxy s'intercale entre le service et PostgreSQL pour couper puis rétablir la base sans changer de port.
@Testcontainers
class BaseDeDonneesTest {

    private static final Network RESEAU = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("troc")
            .withUsername("troc")
            .withPassword("troc-test")
            .withNetwork(RESEAU)
            .withNetworkAliases("postgres");

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(RESEAU);

    private static Proxy proxy;

    @BeforeAll
    static void creerProxy() throws Exception {
        ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        proxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
    }

    @AfterEach
    void retablirLaBase() throws Exception {
        proxy.enable();
    }

    private static ConfigurableApplicationContext demarrer() {
        String url = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666) + "/troc";
        return new SpringApplicationBuilder(TrocApplication.class).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=troc",
                "--spring.datasource.password=troc-test",
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
    void sante_db_up_et_migration_appliquee() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer()) {
            assertThat(statut(contexte, "/actuator/health/db")).isEqualTo(200);
            assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(200);

            try (Connection connexion = contexte.getBean(DataSource.class).getConnection();
                    ResultSet resultat = connexion.createStatement().executeQuery(
                            "select count(*) from flyway_schema_history where version = '1' and success")) {
                resultat.next();
                assertThat(resultat.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void base_indisponible_au_demarrage_le_service_attend_puis_demarre() throws Exception {
        proxy.disable();
        ScheduledExecutorService planificateur = Executors.newSingleThreadScheduledExecutor();
        planificateur.schedule(() -> {
            proxy.enable();
            return null;
        }, 5, TimeUnit.SECONDS);

        long debut = System.nanoTime();
        try (ConfigurableApplicationContext contexte = demarrer()) {
            assertThat(Duration.ofNanos(System.nanoTime() - debut)).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
            assertThat(statut(contexte, "/actuator/health/readiness")).isEqualTo(200);
        } finally {
            planificateur.shutdownNow();
        }
    }

    @Test
    void base_coupee_en_fonctionnement_readiness_down_sans_toucher_liveness() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer()) {
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
