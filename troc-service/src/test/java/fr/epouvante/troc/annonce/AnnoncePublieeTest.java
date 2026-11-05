package fr.epouvante.troc.annonce;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import io.micrometer.core.instrument.MeterRegistry;
import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.path.json.JsonPath;

// US1a — émission de l'événement AnnoncePubliee après l'enregistrement (docs/cadrage.md §1.2 points 7, 8 et 10 ;
// contrat : docs/schemas/evenement-annonce-publiee.md). RabbitMQ réel, même version que le cluster ; compte troc avec
// les droits du cluster (écriture sur l'exchange annonces seulement, ADR 0010) : le service ne déclare rien.
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class AnnoncePublieeTest {

    private static final String FILE = "notification.annonce-publiee";

    @Container
    private static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine")
            .withAdminUser("admin")
            .withAdminPassword("admin-test");

    private static CachingConnectionFactory connexionAdmin;
    private static RabbitTemplate lecteur;

    @BeforeAll
    static void topologieEtCompteTroc() throws Exception {
        // Même topologie et mêmes droits que deploy/scripts/rabbitmq-secrets.sh (sans la DLQ, inutile ici).
        RABBITMQ.execInContainer("rabbitmqctl", "add_user", "troc", "troc-test");
        RABBITMQ.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", "troc", "^$", "^annonces$", "^$");
        connexionAdmin = new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getAmqpPort());
        connexionAdmin.setUsername("admin");
        connexionAdmin.setPassword("admin-test");
        RabbitAdmin admin = new RabbitAdmin(connexionAdmin);
        TopicExchange annonces = new TopicExchange("annonces", true, false);
        Queue file = QueueBuilder.durable(FILE).quorum().deliveryLimit(3).build();
        admin.declareExchange(annonces);
        admin.declareQueue(file);
        admin.declareBinding(BindingBuilder.bind(file).to(annonces).with("annonce.publiee"));
        lecteur = new RabbitTemplate(connexionAdmin);
    }

    @AfterAll
    static void fermer() {
        connexionAdmin.destroy();
    }

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry proprietes) {
        KeycloakPartage.proprietes(proprietes);
        proprietes.add("spring.rabbitmq.host", RABBITMQ::getHost);
        proprietes.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        proprietes.add("spring.rabbitmq.username", () -> "troc");
        proprietes.add("spring.rabbitmq.password", () -> "troc-test");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient base;

    @Autowired
    MeterRegistry metriques;

    private String alice;

    @BeforeEach
    void contexte() {
        base.sql("delete from annonce").update();
        new RabbitAdmin(connexionAdmin).purgeQueue(FILE, false);
        alice = KeycloakPartage.jeton("alice");
    }

    @Test
    @DisplayName("L'événement AnnoncePubliee est émis après l'enregistrement")
    void l_evenement_annonce_publiee_est_emis_apres_l_enregistrement() {
        JsonPath annonce = given().baseUri("http://localhost:" + port).basePath("/api/troc")
                .auth().oauth2(alice)
                .multiPart(new MultiPartSpecBuilder("Halloween, la nuit des masques").controlName("titre")
                        .emptyFileName().charset("UTF-8").build())
                .multiPart(new MultiPartSpecBuilder("Films").controlName("categorie").emptyFileName().build())
                .multiPart(new MultiPartSpecBuilder("ECHANGE").controlName("type").emptyFileName().build())
                .multiPart("photo", "affiche.png", Photos.png(80 * Photos.KO), "image/png")
                .post("/annonces")
                .then().statusCode(201)
                .extract().jsonPath();
        String id = annonce.getString("id");

        // L'annonce est enregistrée en base.
        assertThat(base.sql("select count(*) from annonce where id = cast(:id as uuid)").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);

        // Un événement AnnoncePubliee est publié : id, catégorie, auteur, titre, date de création, URL de la photo.
        Message message = lecteur.receive(FILE, 10_000);
        assertThat(message).as("message reçu dans " + FILE).isNotNull();
        JsonPath evenement = JsonPath.from(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(evenement.getInt("version")).isEqualTo(1);
        assertThat(evenement.getString("annonceId")).isEqualTo(id);
        assertThat(evenement.getString("categorie")).isEqualTo("Films");
        assertThat(evenement.getString("auteurId")).isEqualTo(KeycloakPartage.sub(alice));
        assertThat(evenement.getString("titre")).isEqualTo("Halloween, la nuit des masques");
        assertThat(evenement.getString("creeLe")).isEqualTo(annonce.getString("creeLe"));
        assertThat(evenement.getString("photoUrl")).isEqualTo(annonce.getString("photoUrl"));

        // Message durable, au format JSON, identifié par l'annonce (clé anti-doublon du consommateur, US3).
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id);
        assertThat(message.getMessageProperties().getType()).isEqualTo("AnnoncePubliee");
        Map<String, Object> entetes = message.getMessageProperties().getHeaders();
        assertThat(entetes.get("correlationId")).isInstanceOf(String.class);
        // Pas d'en-tête de type Java : le consommateur ne dépend pas des classes de troc-service.
        assertThat(entetes).doesNotContainKey("__TypeId__");
    }

    // Limite assumée (docs/cadrage.md §1.2 point 8) : l'annonce reste publiée, l'échec d'émission est compté
    // (métrique annonces.publiees.emission.echecs) et journalisé.
    @Test
    void un_evenement_route_vers_aucune_file_est_compte_comme_un_echec_sans_annuler_la_publication() {
        RabbitAdmin admin = new RabbitAdmin(connexionAdmin);
        Binding liaison = BindingBuilder.bind(new Queue(FILE)).to(new TopicExchange("annonces")).with("annonce.publiee");
        admin.removeBinding(liaison);
        double avant = metriques.counter("annonces.publiees.emission.echecs").count();
        try {
            given().baseUri("http://localhost:" + port).basePath("/api/troc")
                    .auth().oauth2(alice)
                    .multiPart(new MultiPartSpecBuilder("Masque de Jason").controlName("titre").emptyFileName().build())
                    .multiPart(new MultiPartSpecBuilder("Goodies").controlName("categorie").emptyFileName().build())
                    .multiPart(new MultiPartSpecBuilder("DON").controlName("type").emptyFileName().build())
                    .multiPart("photo", "jason.webp", Photos.webp(40 * Photos.KO), "image/webp")
                    .post("/annonces")
                    .then().statusCode(201);

            assertThat(metriques.counter("annonces.publiees.emission.echecs").count()).isEqualTo(avant + 1);
        } finally {
            admin.declareBinding(liaison);
        }
    }

    @Test
    void une_annonce_refusee_n_emet_aucun_evenement() {
        given().baseUri("http://localhost:" + port).basePath("/api/troc")
                .auth().oauth2(alice)
                .multiPart(new MultiPartSpecBuilder("Hi").controlName("titre").emptyFileName().build())
                .multiPart("photo", "affiche.png", Photos.png(80 * Photos.KO), "image/png")
                .post("/annonces")
                .then().statusCode(400);

        assertThat(lecteur.receive(FILE, 2_000)).isNull();
    }
}
