package fr.epouvante.notification.abonnement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

// US2 — Gérer mes catégories d'intérêt (docs/backlog/US2-gerer-centres-interet.md).
// Tests d'acceptation par l'API, en boîte noire : un test par scénario Gherkin, même nom (@DisplayName).
// PostgreSQL et Keycloak réels (Testcontainers). Chemins réels sous /api/notifications (ADR 0006, décision D2).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class GererAbonnementsAcceptationTest {

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry proprietes) {
        KeycloakPartage.proprietes(proprietes);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient base;

    private String alice;

    @BeforeEach
    void contexte() {
        base.sql("delete from abonnement").update();
        alice = KeycloakPartage.jeton("alice");
    }

    private RequestSpecification api(String jeton) {
        RequestSpecification requete = given().baseUri("http://localhost:" + port).basePath("/api/notifications");
        return jeton == null ? requete : requete.auth().oauth2(jeton);
    }

    private ValidatableResponse abonnements(String jeton) {
        return api(jeton).get("/abonnements").then().log().ifValidationFails();
    }

    // Libellé passé dans le chemin, encodé par RestAssured (« Jeux de société »).
    private ValidatableResponse abonner(String jeton, String categorie) {
        return api(jeton).put("/abonnements/{categorie}", categorie).then().log().ifValidationFails();
    }

    private ValidatableResponse desabonner(String jeton, String categorie) {
        return api(jeton).delete("/abonnements/{categorie}", categorie).then().log().ifValidationFails();
    }

    @Test
    @DisplayName("Un nouveau membre n'a aucune catégorie d'intérêt")
    void un_nouveau_membre_n_a_aucune_categorie_d_interet() {
        abonnements(alice).statusCode(200).body("$", empty());
    }

    @Test
    @DisplayName("Ajouter une catégorie d'intérêt")
    void ajouter_une_categorie_d_interet() {
        abonner(alice, "BD").statusCode(204);

        abonnements(alice).statusCode(200).body("$", contains("BD"));
    }

    @Test
    @DisplayName("S'abonner à plusieurs catégories")
    void s_abonner_a_plusieurs_categories() {
        abonner(alice, "BD").statusCode(204);

        abonner(alice, "Films").statusCode(204);

        abonnements(alice).statusCode(200).body("$", containsInAnyOrder("BD", "Films"));
    }

    @Test
    @DisplayName("Retirer une catégorie d'intérêt")
    void retirer_une_categorie_d_interet() {
        abonner(alice, "BD").statusCode(204);
        abonner(alice, "Films").statusCode(204);

        desabonner(alice, "BD").statusCode(204);

        abonnements(alice).statusCode(200).body("$", contains("Films"));
    }

    @Test
    @DisplayName("Une catégorie inconnue est refusée")
    void une_categorie_inconnue_est_refusee() {
        abonner(alice, "BD").statusCode(204);

        abonner(alice, "Voitures").statusCode(400);

        abonnements(alice).statusCode(200).body("$", contains("BD"));
    }

    @Test
    @DisplayName("Les abonnements sont propres à chaque membre")
    void les_abonnements_sont_propres_a_chaque_membre() {
        abonner(alice, "BD").statusCode(204);

        abonnements(KeycloakPartage.jeton("bob")).statusCode(200).body("$", not(hasItem("BD")));
    }

    @Test
    @DisplayName("Gérer ses catégories sans jeton est refusé")
    void gerer_ses_categories_sans_jeton_est_refuse() {
        abonnements(null).statusCode(401);
    }

    // Règles complémentaires (décision D3 du 05/11/2026) : ajout et retrait idempotents, libellé avec espace et accent.

    @Test
    @DisplayName("Rejouer l'ajout d'une catégorie ne crée pas de doublon")
    void rejouer_l_ajout_ne_cree_pas_de_doublon() {
        abonner(alice, "BD").statusCode(204);

        abonner(alice, "BD").statusCode(204);

        abonnements(alice).statusCode(200).body("$", contains("BD"));
    }

    @Test
    @DisplayName("Retirer une catégorie non suivie réussit sans rien changer")
    void retirer_une_categorie_non_suivie_reussit() {
        abonner(alice, "Films").statusCode(204);

        desabonner(alice, "BD").statusCode(204);

        abonnements(alice).statusCode(200).body("$", contains("Films"));
    }

    @Test
    @DisplayName("Retirer une catégorie inconnue est refusé")
    void retirer_une_categorie_inconnue_est_refuse() {
        desabonner(alice, "Voitures").statusCode(400);
    }

    @Test
    @DisplayName("Un libellé avec espace et accent est accepté")
    void un_libelle_avec_espace_et_accent_est_accepte() {
        abonner(alice, "Jeux de société").statusCode(204);

        abonnements(alice).statusCode(200).body("$", contains("Jeux de société"));
    }

    @Test
    @DisplayName("Ajouter une catégorie sans jeton est refusé")
    void ajouter_une_categorie_sans_jeton_est_refuse() {
        abonner(null, "BD").statusCode(401);
    }

    @Test
    @DisplayName("Retirer une catégorie sans jeton est refusé")
    void retirer_une_categorie_sans_jeton_est_refuse() {
        desabonner(null, "BD").statusCode(401);
    }

    // ADR 0023 : la protection CSRF est ignorée sur /api/** à condition qu'aucun cookie ne soit posé.
    @Test
    void l_api_ne_cree_ni_session_ni_cookie() {
        abonner(alice, "BD").statusCode(204).header("Set-Cookie", nullValue());
        abonner(null, "BD").statusCode(401).header("Set-Cookie", nullValue());
        abonnements(alice).statusCode(200).header("Set-Cookie", nullValue());
    }
}
