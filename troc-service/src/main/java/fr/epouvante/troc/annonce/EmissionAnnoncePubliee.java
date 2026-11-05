package fr.epouvante.troc.annonce;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

// Émission de AnnoncePubliee après le commit de l'annonce (docs/cadrage.md §1.2 point 8) : message durable, publisher
// confirm attendu (délai borné), retour si le message n'est routé vers aucune file. Un échec ne remet pas en cause la
// publication déjà enregistrée : il est journalisé et compté (perte possible entre commit et publication, limite
// assumée, outbox au plan de remédiation, cadrage §8).
@Component
class EmissionAnnoncePubliee {

    private static final Logger LOG = LoggerFactory.getLogger(EmissionAnnoncePubliee.class);

    // Annonce enregistrée dans la transaction en cours ; émise seulement si la transaction est validée.
    record AnnonceEnregistree(Annonce annonce, String correlationId) {
    }

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final Counter echecs;
    private final String exchange;
    private final String cle;
    private final long delaiConfirmationMs;

    EmissionAnnoncePubliee(RabbitTemplate rabbit, JsonMapper json, MeterRegistry metriques,
            @Value("${app.annonces.exchange}") String exchange,
            @Value("${app.annonces.cle-publiee}") String cle,
            @Value("${app.annonces.delai-confirmation}") java.time.Duration delaiConfirmation) {
        this.rabbit = rabbit;
        this.json = json;
        this.exchange = exchange;
        this.cle = cle;
        this.delaiConfirmationMs = delaiConfirmation.toMillis();
        this.echecs = Counter.builder("annonces.publiees.emission.echecs")
                .description("Événements AnnoncePubliee non confirmés par RabbitMQ (annonce enregistrée)")
                .register(metriques);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void emettre(AnnonceEnregistree enregistree) {
        Annonce annonce = enregistree.annonce();
        Message message = MessageBuilder.withBody(json.writeValueAsBytes(AnnoncePubliee.de(annonce)))
                .setContentType("application/json")
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(annonce.id().toString())
                .setType("AnnoncePubliee")
                .setHeader("correlationId", enregistree.correlationId())
                .build();
        CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange, cle, message, confirmation);
            CorrelationData.Confirm reponse = confirmation.getFuture().get(delaiConfirmationMs, TimeUnit.MILLISECONDS);
            if (!reponse.ack()) {
                echec(annonce, "refusé par RabbitMQ (" + reponse.reason() + ")");
            } else if (confirmation.getReturned() != null) {
                echec(annonce, "routé vers aucune file (" + confirmation.getReturned().getReplyText() + ")");
            } else {
                LOG.info("AnnoncePubliee émis pour l'annonce {} (correlationId {})", annonce.id(),
                        enregistree.correlationId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            echec(annonce, "attente de la confirmation interrompue");
        } catch (Exception e) {
            echec(annonce, e.getClass().getSimpleName());
        }
    }

    private void echec(Annonce annonce, String motif) {
        echecs.increment();
        LOG.error("AnnoncePubliee non émis pour l'annonce {} : {}", annonce.id(), motif);
    }
}
