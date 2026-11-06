package fr.epouvante.notification.abonnement;

import static io.restassured.RestAssured.given;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

// Keycloak avec le realm de test (docs/cadrage.md §1.3.12), démarré une seule fois pour toutes les classes de tests
// d'acceptation (US2, US3) : un conteneur partagé évite environ 20 s de démarrage par classe. Arrêté par Testcontainers (Ryuk).
public final class KeycloakPartage {

    // Même version que sur le cluster (ADR 0003).
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/realm-test.json"),
                    "/opt/keycloak/data/import/realm-test.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/test").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    private KeycloakPartage() {
    }

    private static String realm() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/test";
    }

    public static void proprietes(DynamicPropertyRegistry proprietes) {
        proprietes.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakPartage::realm);
        proprietes.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realm() + "/protocol/openid-connect/certs");
    }

    // Comptes du realm de test : mot de passe = nom d'utilisateur.
    public static String jeton(String utilisateur) {
        return given()
                .formParam("grant_type", "password")
                .formParam("client_id", "client-test")
                .formParam("username", utilisateur)
                .formParam("password", utilisateur)
                .post(realm() + "/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }
}
