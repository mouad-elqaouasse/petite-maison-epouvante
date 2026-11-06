package fr.epouvante.notification.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import fr.epouvante.notification.NotificationApplication;
import io.micrometer.core.instrument.MeterRegistry;

// US3 — fiabilité du consommateur de AnnoncePubliee (docs/cadrage.md §1.2 point 8 ; ADR 0025 et 0026) :
// basic.reject et jamais basic.nack (exp. 2 §6), 4 livraisons puis DLQ, ack après le commit, droits du compte
// notification, résilience à l'arrêt du service. RabbitMQ chargé avec la vraie topologie (ADR 0026) ; PostgreSQL
// derrière Toxiproxy pour simuler l'échec de l'enregistrement.
@Testcontainers
class ConsommateurFiabiliteTest {

    private static final Network RESEAU = Network.newNetwork();

    @Container
    private static final RabbitMQContainer RABBITMQ = RabbitMqAnnonces.conteneur();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("notification")
            .withUsername("notification")
            .withPassword("notification-test")
            .withNetwork(RESEAU)
            .withNetworkAliases("postgres");

    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(RESEAU);

    private static final Duration ATTENTE = Duration.ofSeconds(60);

    private static Proxy proxy;

    @BeforeAll
    static void creerProxy() throws Exception {
        ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        proxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
    }

    @BeforeEach
    void filesVides() throws Exception {
        RabbitMqAnnonces.viderLesFiles(RABBITMQ);
    }

    @AfterEach
    void retablirLaBase() throws Exception {
        proxy.enable();
    }

    private static ConfigurableApplicationContext demarrer(boolean ecoute) {
        String url = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666) + "/notification";
        return new SpringApplicationBuilder(NotificationApplication.class).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=notification",
                "--spring.datasource.password=notification-test",
                "--spring.rabbitmq.host=" + RABBITMQ.getHost(),
                "--spring.rabbitmq.port=" + RABBITMQ.getAmqpPort(),
                "--spring.rabbitmq.username=notification",
                "--spring.rabbitmq.password=" + RabbitMqAnnonces.MDP_NOTIFICATION,
                "--app.notification.ecoute=" + ecoute,
                "--app.notification.pause-avant-rejet=200ms",
                "--server.port=0",
                "--management.server.port=0");
    }

    // Lecture directe, hors du service et de Toxiproxy : ce qu'une autre connexion voit, donc ce qui est commité.
    private static int notifications(UUID annonceId) throws Exception {
        try (Connection connexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notification",
                "notification-test");
                PreparedStatement requete = connexion.prepareStatement(
                        "select count(*) from notification where annonce_id = ?")) {
            requete.setObject(1, annonceId);
            try (ResultSet resultat = requete.executeQuery()) {
                resultat.next();
                return resultat.getInt(1);
            }
        }
    }

    private static void abonner(String membreId, String code) throws Exception {
        try (Connection connexion = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notification",
                "notification-test");
                PreparedStatement requete = connexion.prepareStatement(
                        "insert into abonnement (membre_id, categorie) values (?, ?) on conflict do nothing")) {
            requete.setString(1, membreId);
            requete.setString(2, code);
            requete.executeUpdate();
        }
    }

    private static double rejets(ConfigurableApplicationContext contexte) {
        return contexte.getBean(MeterRegistry.class).counter("notification.annonces.publiees.rejets").count();
    }

    private static GetResponse attendreLaDlq() {
        return await().atMost(ATTENTE).until(() -> RabbitMqAnnonces.lireDlq(RABBITMQ), m -> m != null);
    }

    // Le broker a placé le message en DLQ parce que la limite de livraisons est atteinte, depuis la file du service.
    private static void verifierMortParLimiteDeLivraisons(GetResponse message, String messageId) {
        assertThat(message.getProps().getMessageId()).isEqualTo(messageId);
        // En-têtes AMQP de type LongString : comparés par leur texte.
        Map<String, Object> entetes = message.getProps().getHeaders();
        assertThat(entetes.get("x-first-death-reason")).hasToString("delivery_limit");
        // 4 livraisons comptées par le broker : 1 livraison + 3 réessais.
        assertThat(entetes.get("x-delivery-count")).hasToString("4");
        List<Map<String, Object>> morts = RabbitMqAnnonces.xDeath(message);
        assertThat(morts).singleElement().satisfies(mort -> {
            assertThat(mort.get("queue")).hasToString(RabbitMqAnnonces.FILE);
            assertThat(mort.get("reason")).hasToString("delivery_limit");
        });
    }

    @Test
    @DisplayName("Un message impossible à traiter est retenté 3 fois puis placé en DLQ")
    void un_message_impossible_a_traiter_est_retente_3_fois_puis_place_en_dlq() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer(true)) {
            proxy.disable();
            UUID annonce = UUID.randomUUID();

            RabbitMqAnnonces.publier(RABBITMQ, annonce, "Figurines", "auteur-" + annonce, "Figurine Chucky 1988");

            GetResponse message = attendreLaDlq();
            verifierMortParLimiteDeLivraisons(message, annonce.toString());
            // 4 livraisons, 4 rejets ; jamais acquitté.
            assertThat(rejets(contexte)).isEqualTo(4.0);
            assertThat(RabbitMqAnnonces.messagesPrets(RABBITMQ, RabbitMqAnnonces.FILE)).isZero();
            proxy.enable();
            assertThat(notifications(annonce)).isZero();
        }
    }

    // Point 1 de la consigne US3 : un message illisible finit en DLQ après la limite de livraisons, sans boucler.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pas du JSON", "version inconnue", "catégorie inconnue", "champ manquant"})
    @DisplayName("Un message illisible est rejeté 4 fois puis placé en DLQ")
    void un_message_illisible_est_rejete_4_fois_puis_place_en_dlq(String cas) throws Exception {
        UUID annonce = UUID.randomUUID();
        String corps = switch (cas) {
            case "pas du JSON" -> "<annonce>Chucky</annonce>";
            case "version inconnue" -> RabbitMqAnnonces.evenement(annonce, "Figurines", "auteur", "Chucky")
                    .replace("\"version\":1", "\"version\":2");
            case "catégorie inconnue" -> RabbitMqAnnonces.evenement(annonce, "Voitures", "auteur", "Chucky");
            default -> RabbitMqAnnonces.evenement(annonce, "Figurines", "auteur", "Chucky")
                    .replace("\"auteurId\":\"auteur\",", "");
        };
        try (ConfigurableApplicationContext contexte = demarrer(true)) {
            RabbitMqAnnonces.publier(RABBITMQ, annonce.toString(), corps);

            verifierMortParLimiteDeLivraisons(attendreLaDlq(), annonce.toString());
            assertThat(rejets(contexte)).isEqualTo(4.0);
            assertThat(notifications(annonce)).isZero();
        }
    }

    @Test
    @DisplayName("Le message est acquitté après le commit des notifications")
    void le_message_est_acquitte_apres_le_commit() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer(false)) {
            UUID annonce = UUID.randomUUID();
            abonner("abonne-" + annonce, "FIGURINES");
            Message message = message(RabbitMqAnnonces.evenement(annonce, "Figurines", "auteur-" + annonce, "Chucky"));
            Channel canal = mock(Channel.class);
            AtomicInteger visiblesAuMomentDeLAck = new AtomicInteger(-1);
            doAnswer(appel -> {
                visiblesAuMomentDeLAck.set(notifications(annonce));
                return null;
            }).when(canal).basicAck(42L, false);

            contexte.getBean(ConsommateurAnnoncePubliee.class).onMessage(message, canal);

            verify(canal).basicAck(42L, false);
            // CONFIRMATION de l'auteur et ALERTE de l'abonné, déjà visibles d'une autre connexion : commit fait.
            assertThat(visiblesAuMomentDeLAck).hasValue(2);
        }
    }

    @Test
    @DisplayName("Un échec est rejeté avec remise en file, jamais par nack")
    void un_echec_est_rejete_avec_remise_en_file_jamais_par_nack() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer(false)) {
            Channel canal = mock(Channel.class);

            contexte.getBean(ConsommateurAnnoncePubliee.class).onMessage(message("pas du JSON"), canal);

            verify(canal).basicReject(42L, true);
            verify(canal, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            verify(canal, never()).basicAck(anyLong(), anyBoolean());
        }
    }

    private static Message message(String corps) {
        MessageProperties proprietes = new MessageProperties();
        proprietes.setDeliveryTag(42L);
        proprietes.setContentType("application/json");
        proprietes.setMessageId(UUID.randomUUID().toString());
        return new Message(corps.getBytes(StandardCharsets.UTF_8), proprietes);
    }

    // Point 5 : compte notification au moindre privilège (ADR 0026) — lecture de sa file seulement.
    @Test
    @DisplayName("Le compte notification lit sa file et rien d'autre")
    void le_compte_notification_lit_sa_file_et_rien_d_autre() throws Exception {
        try (com.rabbitmq.client.Connection notification = RabbitMqAnnonces.connexion(RABBITMQ, "notification",
                RabbitMqAnnonces.MDP_NOTIFICATION)) {
            try (Channel canal = notification.createChannel()) {
                assertThat(canal.basicGet(RabbitMqAnnonces.FILE, false)).isNull();
            }
            assertThatThrownBy(() -> notification.createChannel().basicGet(RabbitMqAnnonces.DLQ, false))
                    .hasStackTraceContaining("ACCESS_REFUSED");
            assertThatThrownBy(() -> {
                Channel canal = notification.createChannel();
                canal.confirmSelect();
                canal.basicPublish(RabbitMqAnnonces.EXCHANGE, RabbitMqAnnonces.CLE, null, "{}".getBytes());
                canal.waitForConfirmsOrDie(5_000);
            }).hasStackTraceContaining("ACCESS_REFUSED");
            assertThatThrownBy(() -> notification.createChannel().queueDeclare("notification.autre", true, false,
                    false, null)).hasStackTraceContaining("ACCESS_REFUSED");
            // queue.purge ne demande que read dans RabbitMQ : le compte peut vider sa propre file (limite du modèle
            // configure / write / read, sans droit plus fin).
        }
    }

    // Point 6 : service arrêté, annonce publiée, redémarrage, la notification arrive.
    @Test
    @DisplayName("Une annonce publiée pendant l'arrêt du service est notifiée à son redémarrage")
    void une_annonce_publiee_pendant_l_arret_est_notifiee_au_redemarrage() throws Exception {
        UUID annonce = UUID.randomUUID();
        demarrer(true).close();

        RabbitMqAnnonces.publier(RABBITMQ, annonce, "Figurines", "auteur-" + annonce, "Figurine Chucky 1988");
        assertThat(RabbitMqAnnonces.messagesPrets(RABBITMQ, RabbitMqAnnonces.FILE)).isEqualTo(1);

        try (ConfigurableApplicationContext contexte = demarrer(true)) {
            await().atMost(ATTENTE).until(() -> notifications(annonce) == 1);
            await().atMost(ATTENTE).until(() -> RabbitMqAnnonces.messagesPrets(RABBITMQ, RabbitMqAnnonces.FILE) == 0);
        }
    }
}
