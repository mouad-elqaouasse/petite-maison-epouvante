package fr.epouvante.notification.notifier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import fr.epouvante.notification.abonnement.KeycloakPartage;
import io.restassured.specification.RequestSpecification;
import tools.jackson.databind.json.JsonMapper;

// US3 — Être notifié d'une nouvelle annonce (docs/backlog/US3-notifier-nouvelle-annonce.md).
// Tests d'acceptation en boîte noire : l'événement est publié comme troc-service (compte troc, contrat v1) dans un
// RabbitMQ chargé avec la vraie topologie (ADR 0026) ; abonnements et notifications passent par l'API, avec des jetons
// Keycloak réels. Attente bornée de l'apparition des notifications (Awaitility).
// Le scénario DLQ est dans ConsommateurFiabiliteTest (coupure de la base, note d'US3).
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "app.notification.ecoute=true",
        "app.notification.pause-avant-rejet=200ms"})
// Contexte fermé après la classe : son consommateur ne doit pas rester branché sur un broker arrêté.
@DirtiesContext
class NotifierNouvelleAnnonceAcceptationTest {

    @Container
    private static final RabbitMQContainer RABBITMQ = RabbitMqAnnonces.conteneur();

    private static final Duration ATTENTE = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry proprietes) {
        KeycloakPartage.proprietes(proprietes);
        proprietes.add("spring.rabbitmq.host", RABBITMQ::getHost);
        proprietes.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        proprietes.add("spring.rabbitmq.username", () -> "notification");
        proprietes.add("spring.rabbitmq.password", () -> RabbitMqAnnonces.MDP_NOTIFICATION);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient base;

    private String alice;
    private String bob;
    private String carole;

    @BeforeEach
    void contexte() throws Exception {
        RabbitMqAnnonces.viderLesFiles(RABBITMQ);
        base.sql("delete from abonnement").update();
        base.sql("delete from notification").update();
        alice = KeycloakPartage.jeton("alice");
        bob = KeycloakPartage.jeton("bob");
        carole = KeycloakPartage.jeton("carole");
        abonner(bob, "Figurines");
        abonner(carole, "BD");
    }

    private RequestSpecification api(String jeton) {
        RequestSpecification requete = given().baseUri("http://localhost:" + port).basePath("/api/notifications");
        return jeton == null ? requete : requete.auth().oauth2(jeton);
    }

    private void abonner(String jeton, String categorie) {
        api(jeton).put("/abonnements/{categorie}", categorie).then().statusCode(204);
    }

    private List<Map<String, Object>> notifications(String jeton) {
        return api(jeton).get().then().log().ifValidationFails().statusCode(200).extract().jsonPath().getList("$");
    }

    private List<Map<String, Object>> notifications(String jeton, UUID annonceId) {
        return notifications(jeton).stream().filter(n -> annonceId.toString().equals(n.get("annonceId"))).toList();
    }

    private void attendreUneNotification(String jeton, UUID annonceId) {
        await().atMost(ATTENTE).until(() -> !notifications(jeton, annonceId).isEmpty());
    }

    // sub du jeton : identifiant du membre, comme troc-service le met dans auteurId.
    private static String sub(String jeton) {
        String charge = new String(Base64.getUrlDecoder().decode(jeton.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonMapper.builder().build().readTree(charge).get("sub").asString();
    }

    private UUID alicePublie(String categorie, String titre) throws Exception {
        UUID annonceId = UUID.randomUUID();
        RabbitMqAnnonces.publier(RABBITMQ, annonceId, categorie, sub(alice), titre);
        return annonceId;
    }

    @Test
    @DisplayName("L'auteur reçoit une confirmation")
    void l_auteur_recoit_une_confirmation() throws Exception {
        UUID annonce = alicePublie("Figurines", "Figurine Chucky 1988");

        attendreUneNotification(alice, annonce);
        assertThat(notifications(alice, annonce)).singleElement().satisfies(n -> {
            assertThat(n).containsEntry("type", "CONFIRMATION")
                    .containsEntry("texte", "Votre annonce est publiée")
                    .containsEntry("titreAnnonce", "Figurine Chucky 1988")
                    .containsKey("creeLe");
        });
    }

    @Test
    @DisplayName("Un membre abonné à la catégorie reçoit une alerte")
    void un_membre_abonne_a_la_categorie_recoit_une_alerte() throws Exception {
        UUID annonce = alicePublie("Figurines", "Figurine Chucky 1988");

        attendreUneNotification(bob, annonce);
        assertThat(notifications(bob, annonce)).singleElement().satisfies(n -> {
            assertThat(n).containsEntry("type", "ALERTE")
                    .containsEntry("texte", "Nouvelle annonce")
                    .containsEntry("titreAnnonce", "Figurine Chucky 1988");
        });
    }

    @Test
    @DisplayName("Un membre non abonné à la catégorie ne reçoit rien")
    void un_membre_non_abonne_a_la_categorie_ne_recoit_rien() throws Exception {
        UUID annonce = alicePublie("Figurines", "Figurine Chucky 1988");

        // Les notifications d'un même événement sont écrites dans la même transaction : bob notifié, l'événement
        // est traité.
        attendreUneNotification(bob, annonce);
        assertThat(notifications(carole, annonce)).isEmpty();
    }

    @Test
    @DisplayName("Un auteur abonné à la catégorie reçoit seulement la confirmation")
    void un_auteur_abonne_a_la_categorie_recoit_seulement_la_confirmation() throws Exception {
        abonner(alice, "Figurines");

        UUID annonce = alicePublie("Figurines", "Figurine Chucky 1988");

        attendreUneNotification(alice, annonce);
        assertThat(notifications(alice, annonce)).singleElement()
                .satisfies(n -> assertThat(n).containsEntry("type", "CONFIRMATION"));
    }

    @Test
    @DisplayName("Un message reçu en double ne crée qu'une seule notification")
    void un_message_recu_en_double_ne_cree_qu_une_seule_notification() throws Exception {
        UUID annonce = UUID.randomUUID();
        String evenement = RabbitMqAnnonces.evenement(annonce, "Figurines", sub(alice), "Figurine Chucky 1988");
        RabbitMqAnnonces.publier(RABBITMQ, annonce.toString(), evenement);
        attendreUneNotification(bob, annonce);

        RabbitMqAnnonces.publier(RABBITMQ, annonce.toString(), evenement);
        // Un seul consommateur, messages traités dans l'ordre : quand l'annonce suivante est notifiée, le doublon a
        // été traité.
        UUID suivante = alicePublie("Figurines", "Affiche Halloween 1978");
        attendreUneNotification(bob, suivante);

        assertThat(notifications(bob, annonce)).singleElement()
                .satisfies(n -> assertThat(n).containsEntry("type", "ALERTE"));
        assertThat(notifications(alice, annonce)).singleElement()
                .satisfies(n -> assertThat(n).containsEntry("type", "CONFIRMATION"));
    }

    // Règles complémentaires (notes d'US3) : lecture minimale de GET /notifications.

    @Test
    @DisplayName("Consulter ses notifications sans jeton est refusé")
    void consulter_ses_notifications_sans_jeton_est_refuse() {
        api(null).get().then().statusCode(401);
    }

    @Test
    @DisplayName("Les notifications sont listées des plus récentes aux plus anciennes")
    void les_notifications_sont_listees_des_plus_recentes_aux_plus_anciennes() throws Exception {
        UUID premiere = alicePublie("Figurines", "Première annonce");
        attendreUneNotification(bob, premiere);
        UUID seconde = alicePublie("Figurines", "Seconde annonce");
        attendreUneNotification(bob, seconde);

        assertThat(notifications(bob)).extracting(n -> n.get("annonceId"))
                .containsExactly(seconde.toString(), premiere.toString());
    }

    @Test
    @DisplayName("Un libellé de catégorie avec espace et accent est reconnu")
    void un_libelle_avec_espace_et_accent_est_reconnu() throws Exception {
        abonner(bob, "Jeux de société");

        UUID annonce = alicePublie("Jeux de société", "Jeu Ouija 1972");

        attendreUneNotification(bob, annonce);
    }
}
