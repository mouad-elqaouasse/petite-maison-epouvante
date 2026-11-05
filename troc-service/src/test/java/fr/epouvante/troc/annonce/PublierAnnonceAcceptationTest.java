package fr.epouvante.troc.annonce;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.restassured.builder.MultiPartSpecBuilder;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

// US1a — Publier une annonce de troc avec sa photo (docs/backlog/US1a-publier-annonce.md).
// Tests d'acceptation par l'API, en boîte noire : un test par scénario Gherkin, même nom (@DisplayName).
// StockagePhoto simulé (profil photo-factice, actif dans les tests), PostgreSQL et Keycloak réels (Testcontainers).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class PublierAnnonceAcceptationTest {

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry proprietes) {
        KeycloakDeTest.proprietes(proprietes);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient base;

    @Autowired
    StockagePhotoFactice stockage;

    private String alice;

    @BeforeEach
    void contexte() {
        base.sql("delete from annonce").update();
        alice = KeycloakDeTest.jeton("alice");
    }

    private RequestSpecification api(String jeton) {
        RequestSpecification requete = given().baseUri("http://localhost:" + port).basePath("/api/troc");
        return jeton == null ? requete : requete.auth().oauth2(jeton);
    }

    private int annoncesEnregistrees() {
        return base.sql("select count(*) from annonce").query(Integer.class).single();
    }

    // Annonce valide, modifiable champ par champ par chaque scénario.
    private Formulaire annonceValide() {
        return new Formulaire()
                .avec("titre", "Figurine Chucky 1988")
                .avec("description", "Bon état")
                .avec("categorie", "Figurines")
                .avec("type", "ECHANGE")
                .photo("chucky.jpg", Photos.jpeg(150 * Photos.KO), "image/jpeg");
    }

    private final class Formulaire {
        private final Map<String, String> champs = new LinkedHashMap<>();
        private final Map<String, Object[]> photos = new LinkedHashMap<>();

        Formulaire avec(String nom, String valeur) {
            champs.put(nom, valeur);
            return this;
        }

        Formulaire photo(String nom, byte[] octets, String type) {
            photos.put(nom, new Object[] {octets, type});
            return this;
        }

        Formulaire sansPhoto() {
            photos.clear();
            return this;
        }

        ValidatableResponse publier(String jeton) {
            RequestSpecification requete = api(jeton);
            // Champs texte comme les envoie un navigateur (FormData) : sans nom de fichier, en UTF-8.
            champs.forEach((nom, valeur) -> requete.multiPart(
                    new MultiPartSpecBuilder(valeur).controlName(nom).emptyFileName().charset("UTF-8").build()));
            photos.forEach((nom, photo) -> requete.multiPart("photo", nom, (byte[]) photo[0], (String) photo[1]));
            if (champs.isEmpty() && photos.isEmpty()) {
                requete.multiPart(new MultiPartSpecBuilder("").controlName("vide").emptyFileName().build());
            }
            return requete.post("/annonces").then().log().ifValidationFails();
        }
    }

    @Test
    @DisplayName("Publier une annonce valide avec sa photo")
    void publier_une_annonce_valide_avec_sa_photo() {
        annonceValide().publier(alice)
                .statusCode(201)
                .header("Location", startsWith("/api/troc/annonces/"))
                .body("titre", equalTo("Figurine Chucky 1988"))
                .body("description", equalTo("Bon état"))
                .body("categorie", equalTo("Figurines"))
                .body("type", equalTo("ECHANGE"))
                .body("statut", equalTo("PUBLIEE"))
                .body("creeLe", notNullValue())
                .body("photoUrl", notNullValue());
    }

    @ParameterizedTest(name = "titre de {0} caractères → {1}")
    @DisplayName("Le titre doit faire entre 3 et 80 caractères")
    @CsvSource({"2, 400", "3, 201", "80, 201", "81, 400"})
    void le_titre_doit_faire_entre_3_et_80_caracteres(int longueur, int statut) {
        annonceValide().avec("titre", "é".repeat(longueur)).publier(alice).statusCode(statut);
    }

    @Test
    @DisplayName("Une description de plus de 1000 caractères est refusée")
    void une_description_de_plus_de_1000_caracteres_est_refusee() {
        annonceValide().avec("description", "x".repeat(1001)).publier(alice)
                .statusCode(400)
                .body("erreurs.description", notNullValue());
    }

    @Test
    @DisplayName("Une catégorie hors de la liste fermée est refusée")
    void une_categorie_hors_de_la_liste_fermee_est_refusee() {
        annonceValide().avec("categorie", "Voitures").publier(alice)
                .statusCode(400)
                .body("erreurs.categorie", notNullValue());
    }

    @Test
    @DisplayName("Un type autre que ECHANGE ou DON est refusé")
    void un_type_autre_que_echange_ou_don_est_refuse() {
        annonceValide().avec("type", "VENTE").publier(alice)
                .statusCode(400)
                .body("erreurs.type", notNullValue());
    }

    @Test
    @DisplayName("Une annonce sans photo est refusée")
    void une_annonce_sans_photo_est_refusee() {
        annonceValide().sansPhoto().publier(alice)
                .statusCode(400)
                .body("erreurs.photo", notNullValue());

        assertThat(annoncesEnregistrees()).isZero();
    }

    @Test
    @DisplayName("Une annonce avec plus d'une photo est refusée")
    void une_annonce_avec_plus_d_une_photo_est_refusee() {
        int avant = stockage.photosStockees();

        annonceValide().photo("seconde.jpg", Photos.jpeg(10 * Photos.KO), "image/jpeg").publier(alice)
                .statusCode(400)
                .body("erreurs.photo", notNullValue());

        assertThat(stockage.photosStockees()).isEqualTo(avant);
    }

    @Test
    @DisplayName("Une photo de plus de 2 Mo est refusée")
    void une_photo_de_plus_de_2_mo_est_refusee() {
        int avant = stockage.photosStockees();

        annonceValide().sansPhoto().photo("lourde.jpg", Photos.jpeg(2 * Photos.MO + 1), "image/jpeg").publier(alice)
                .statusCode(400)
                .body("erreurs.photo", notNullValue());

        assertThat(stockage.photosStockees()).isEqualTo(avant);
    }

    @Test
    @DisplayName("Une photo dans un format non autorisé est refusée")
    void une_photo_dans_un_format_non_autorise_est_refusee() {
        int avant = stockage.photosStockees();

        annonceValide().sansPhoto().photo("chauve-souris.gif", Photos.gif(10 * Photos.KO), "image/gif").publier(alice)
                .statusCode(400)
                .body("erreurs.photo", notNullValue());

        assertThat(stockage.photosStockees()).isEqualTo(avant);
    }

    @Test
    @DisplayName("Un fichier déguisé en image est refusé")
    void un_fichier_deguise_en_image_est_refuse() {
        int avant = stockage.photosStockees();

        annonceValide().sansPhoto().photo("photo.jpg", Photos.texte(), "image/jpeg").publier(alice)
                .statusCode(400)
                .body("erreurs.photo", notNullValue());

        assertThat(stockage.photosStockees()).isEqualTo(avant);
    }

    @Test
    @DisplayName("Publier sans jeton est refusé")
    void publier_sans_jeton_est_refuse() {
        annonceValide().publier(null).statusCode(401);

        assertThat(annoncesEnregistrees()).isZero();
    }

    @Test
    @DisplayName("L'auteur est tiré du jeton et non du corps de la requête")
    void l_auteur_est_tire_du_jeton_et_non_du_corps_de_la_requete() {
        String bob = KeycloakDeTest.sub(KeycloakDeTest.jeton("bob"));

        String auteur = annonceValide().avec("auteurId", bob).publier(alice)
                .statusCode(201)
                .extract().path("auteurId");

        assertThat(auteur).isEqualTo(KeycloakDeTest.sub(alice)).isNotEqualTo(bob);
        assertThat(base.sql("select auteur_id from annonce").query(String.class).single())
                .isEqualTo(KeycloakDeTest.sub(alice));
    }

    @Test
    @DisplayName("Consulter la liste sans jeton est refusé")
    void consulter_la_liste_sans_jeton_est_refuse() {
        api(null).get("/annonces").then().statusCode(401);
    }

    // Hors Gherkin (ADR 0023) : l'API ne crée aucune session ni aucun cookie, avec ou sans jeton. C'est ce qui rend la
    // protection CSRF sans objet sur /api/** : il n'y a pas de cookie que le navigateur enverrait à l'insu du membre.
    @Test
    void l_api_ne_cree_ni_session_ni_cookie() {
        annonceValide().publier(alice).statusCode(201)
                .header("Set-Cookie", org.hamcrest.Matchers.nullValue());
        annonceValide().publier(null).statusCode(401)
                .header("Set-Cookie", org.hamcrest.Matchers.nullValue());
        api(alice).get("/annonces").then().statusCode(200)
                .header("Set-Cookie", org.hamcrest.Matchers.nullValue());
    }

    // Hors Gherkin : GET /categories exige aussi un jeton (US1a, Notes : seuls news et articles sont publics).
    @Test
    void consulter_les_categories_sans_jeton_est_refuse() {
        api(null).get("/categories").then().statusCode(401);
    }

    @Test
    @DisplayName("L'annonce publiée apparaît dans la liste")
    void l_annonce_publiee_apparait_dans_la_liste() {
        String photoUrl = annonceValide().avec("titre", "Affiche Halloween 1978").publier(alice)
                .statusCode(201)
                .extract().path("photoUrl");

        api(KeycloakDeTest.jeton("bob"))
                .get("/annonces")
                .then()
                .statusCode(200)
                .body("titre", hasItem("Affiche Halloween 1978"))
                .body("find { it.titre == 'Affiche Halloween 1978' }.photoUrl", equalTo(photoUrl));
    }

    @Test
    @DisplayName("La liste des catégories est fournie par l'API")
    void la_liste_des_categories_est_fournie_par_l_api() {
        api(alice)
                .get("/categories")
                .then()
                .statusCode(200)
                .body("$", containsInAnyOrder("Figurines", "Films", "BD", "Jeux de société", "Goodies"));
    }

    // Hors Gherkin : ordre de la liste (la plus récente d'abord), utile à l'écran « Annonces ».
    @Test
    void la_liste_presente_les_annonces_les_plus_recentes_d_abord() {
        annonceValide().avec("titre", "Première annonce").publier(alice).statusCode(201);
        annonceValide().avec("titre", "Seconde annonce").publier(alice).statusCode(201);

        api(alice).get("/annonces").then().statusCode(200)
                .body("titre", contains("Seconde annonce", "Première annonce"));
    }

    // Hors Gherkin : toutes les erreurs sont renvoyées ensemble, champ par champ, pour l'écran (US1a, « erreurs
    // affichées sous chaque champ »).
    @Test
    void un_formulaire_vide_renvoie_une_erreur_par_champ_obligatoire() {
        new Formulaire().publier(alice)
                .statusCode(400)
                .body("title", equalTo("Annonce invalide"))
                .body("erreurs.titre", notNullValue())
                .body("erreurs.categorie", notNullValue())
                .body("erreurs.type", notNullValue())
                .body("erreurs.photo", notNullValue());

        assertThat(annoncesEnregistrees()).isZero();
    }

    // Hors Gherkin : une photo bien au-delà de 2 Mo (refusée par la limite d'envoi de Spring) donne aussi un 400 lisible.
    @Test
    void une_photo_tres_lourde_est_refusee_par_un_400_sur_le_champ_photo() {
        annonceValide().sansPhoto().photo("enorme.jpg", Photos.jpeg(6 * Photos.MO), "image/jpeg").publier(alice)
                .statusCode(400)
                .body("erreurs.photo", notNullValue());
    }
}
