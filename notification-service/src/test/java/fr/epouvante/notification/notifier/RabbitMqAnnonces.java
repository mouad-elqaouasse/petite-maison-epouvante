package fr.epouvante.notification.notifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.testcontainers.images.builder.Transferable;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// RabbitMQ de test chargé avec la vraie topologie et les vrais droits (ADR 0026) : le fichier utilisé sur le VPS,
// deploy/scripts/rabbitmq-definitions.json, avec des hachés de mots de passe de test. Un compte admin-test, propre aux
// tests, lit la DLQ et vide les files ; il n'existe pas sur le cluster.
final class RabbitMqAnnonces {

    static final String FILE = "notification.annonce-publiee";
    static final String DLQ = FILE + ".dlq";
    static final String EXCHANGE = "annonces";
    static final String CLE = "annonce.publiee";
    static final String MDP_TROC = "troc-test";
    static final String MDP_NOTIFICATION = "notification-test";
    private static final String ADMIN = "admin-test";

    private static final Path DEFINITIONS = Path.of("..", "deploy", "scripts", "rabbitmq-definitions.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RabbitMqAnnonces() {
    }

    // Même version que sur le cluster (≥ 4.3 exigé par le cadrage §1.2.8 : nack ne compte pas, reject compte).
    static RabbitMQContainer conteneur() {
        return new RabbitMQContainer("rabbitmq:4.4.0-management-alpine")
                .withCopyToContainer(Transferable.of(definitions()), "/etc/rabbitmq/definitions.json")
                .withCopyToContainer(Transferable.of("""
                        definitions.import_backend = local_filesystem
                        definitions.local.path = /etc/rabbitmq/definitions.json
                        """), "/etc/rabbitmq/conf.d/20-test.conf");
    }

    private static String definitions() {
        try {
            String modele = Files.readString(DEFINITIONS, StandardCharsets.UTF_8)
                    .replace("__HACHE_TROC__", hache(MDP_TROC))
                    .replace("__HACHE_NOTIFICATION__", hache(MDP_NOTIFICATION));
            ObjectNode racine = (ObjectNode) JSON.readTree(modele);
            ((ArrayNode) racine.get("users")).addObject()
                    .put("name", ADMIN).put("password_hash", hache(ADMIN))
                    .put("hashing_algorithm", "rabbit_password_hashing_sha256")
                    .putArray("tags").add("administrator");
            ((ArrayNode) racine.get("permissions")).addObject()
                    .put("user", ADMIN).put("vhost", "/").put("configure", ".*").put("write", ".*").put("read", ".*");
            return JSON.writeValueAsString(racine);
        } catch (Exception e) {
            throw new IllegalStateException("Définitions RabbitMQ illisibles : " + DEFINITIONS.toAbsolutePath(), e);
        }
    }

    // Format rabbit_password_hashing_sha256, comme rabbitmq-secrets.sh : base64(sel de 4 octets + SHA-256(sel + mdp)).
    private static String hache(String motDePasse) throws Exception {
        byte[] sel = new byte[4];
        new SecureRandom().nextBytes(sel);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(sel);
        byte[] empreinte = sha.digest(motDePasse.getBytes(StandardCharsets.UTF_8));
        byte[] tout = new byte[sel.length + empreinte.length];
        System.arraycopy(sel, 0, tout, 0, sel.length);
        System.arraycopy(empreinte, 0, tout, sel.length, empreinte.length);
        return Base64.getEncoder().encodeToString(tout);
    }

    static Connection connexion(RabbitMQContainer rabbit, String compte, String motDePasse) throws Exception {
        ConnectionFactory usine = new ConnectionFactory();
        usine.setHost(rabbit.getHost());
        usine.setPort(rabbit.getAmqpPort());
        usine.setUsername(compte);
        usine.setPassword(motDePasse);
        return usine.newConnection();
    }

    static Connection admin(RabbitMQContainer rabbit) throws Exception {
        return connexion(rabbit, ADMIN, ADMIN);
    }

    // Corps conforme au contrat v1 (docs/schemas/evenement-annonce-publiee.md).
    static String evenement(UUID annonceId, String categorie, String auteurId, String titre) {
        // Ordre des champs fixe : les tests de messages illisibles retouchent le texte.
        Map<String, Object> champs = new LinkedHashMap<>();
        champs.put("version", 1);
        champs.put("annonceId", annonceId.toString());
        champs.put("categorie", categorie);
        champs.put("auteurId", auteurId);
        champs.put("titre", titre);
        champs.put("creeLe", Instant.now().toString());
        champs.put("photoUrl", "https://res.cloudinary.com/test/image/upload/v1/epouvante/test.jpg");
        return JSON.writeValueAsString(champs);
    }

    // Publication comme troc-service : compte troc, propriétés du contrat, publisher confirm attendu.
    static void publier(RabbitMQContainer rabbit, String messageId, String corps) throws Exception {
        try (Connection troc = connexion(rabbit, "troc", MDP_TROC); Channel canal = troc.createChannel()) {
            canal.confirmSelect();
            AMQP.BasicProperties proprietes = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding("UTF-8")
                    .deliveryMode(2)
                    .messageId(messageId)
                    .type("AnnoncePubliee")
                    .headers(Map.of("correlationId", UUID.randomUUID().toString()))
                    .build();
            canal.basicPublish(EXCHANGE, CLE, true, proprietes, corps.getBytes(StandardCharsets.UTF_8));
            canal.waitForConfirmsOrDie(5_000);
        }
    }

    static void publier(RabbitMQContainer rabbit, UUID annonceId, String categorie, String auteurId, String titre)
            throws Exception {
        publier(rabbit, annonceId.toString(), evenement(annonceId, categorie, auteurId, titre));
    }

    static void viderLesFiles(RabbitMQContainer rabbit) throws Exception {
        try (Connection admin = admin(rabbit); Channel canal = admin.createChannel()) {
            canal.queuePurge(FILE);
            canal.queuePurge(DLQ);
        }
    }

    // Messages prêts (non livrés) dans une file.
    static long messagesPrets(RabbitMQContainer rabbit, String file) throws Exception {
        try (Connection admin = admin(rabbit); Channel canal = admin.createChannel()) {
            return canal.queueDeclarePassive(file).getMessageCount();
        }
    }

    // Lit (et retire) le premier message de la DLQ ; null si elle est vide.
    static GetResponse lireDlq(RabbitMQContainer rabbit) throws Exception {
        try (Connection admin = admin(rabbit); Channel canal = admin.createChannel()) {
            return canal.basicGet(DLQ, true);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> xDeath(GetResponse message) {
        return (List<Map<String, Object>>) message.getProps().getHeaders().get("x-death");
    }
}
