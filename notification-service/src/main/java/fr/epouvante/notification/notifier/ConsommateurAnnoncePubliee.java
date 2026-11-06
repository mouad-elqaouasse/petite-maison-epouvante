package fr.epouvante.notification.notifier;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

// Consommateur de notification.annonce-publiee, en acquittement manuel (ADR 0025) :
// - succès : basicAck après le commit des notifications ;
// - tout échec (JSON illisible, contrat non respecté, base indisponible) : pause, puis basicReject avec remise en
//   file. Jamais basicNack : depuis RabbitMQ 4.3, nack ne fait pas avancer le compteur de x-delivery-limit et le
//   message boucle sans fin (exp. 2 §6). Après 4 livraisons, le broker place le message dans la DLQ.
@Component
class ConsommateurAnnoncePubliee implements ChannelAwareMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(ConsommateurAnnoncePubliee.class);

    private final TraitementAnnoncePubliee traitement;
    private final JsonMapper json;
    private final Duration pauseAvantRejet;
    private final Counter traites;
    private final Counter rejets;

    ConsommateurAnnoncePubliee(TraitementAnnoncePubliee traitement, JsonMapper json, MeterRegistry metriques,
            @Value("${app.notification.pause-avant-rejet}") Duration pauseAvantRejet) {
        this.traitement = traitement;
        this.json = json;
        this.pauseAvantRejet = pauseAvantRejet;
        this.traites = Counter.builder("notification.annonces.publiees.traitees")
                .description("Événements AnnoncePubliee traités et acquittés")
                .register(metriques);
        this.rejets = Counter.builder("notification.annonces.publiees.rejets")
                .description("Livraisons de AnnoncePubliee rejetées (basic.reject, remise en file ; DLQ après 4)")
                .register(metriques);
    }

    @Override
    public void onMessage(Message message, Channel canal) throws Exception {
        long etiquette = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();
        Object correlationId = message.getMessageProperties().getHeaders().get("correlationId");
        int creees;
        try {
            AnnoncePubliee evenement = json.readValue(message.getBody(), AnnoncePubliee.class);
            // Transaction validée au retour : l'ack ci-dessous suit toujours le commit.
            creees = traitement.traiter(evenement);
        } catch (Exception e) {
            rejeter(canal, message, e);
            return;
        }
        canal.basicAck(etiquette, false);
        traites.increment();
        LOG.info("AnnoncePubliee {} traité : {} notification(s) créée(s) (correlationId {})", messageId, creees,
                correlationId);
    }

    private void rejeter(Channel canal, Message message, Exception cause) throws Exception {
        // Livraisons précédentes, comptées par le broker (file quorum) ; la 4e livraison rejetée part en DLQ.
        Object livraisons = message.getMessageProperties().getHeaders().get("x-delivery-count");
        LOG.warn("AnnoncePubliee {} rejeté, remis en file (livraisons précédentes : {}, correlationId {}) : {}",
                message.getMessageProperties().getMessageId(), livraisons == null ? 0 : livraisons,
                message.getMessageProperties().getHeaders().get("correlationId"), motif(cause));
        try {
            Thread.sleep(pauseAvantRejet);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        rejets.increment();
        canal.basicReject(message.getMessageProperties().getDeliveryTag(), true);
    }

    // Classe et message de la cause, sans le corps du message ni la pile.
    private static String motif(Exception e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + e.getMessage());
    }
}
