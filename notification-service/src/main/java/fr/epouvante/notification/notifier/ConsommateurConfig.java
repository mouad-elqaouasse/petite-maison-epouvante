package fr.epouvante.notification.notifier;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Écoute de notification.annonce-publiee (ADR 0025) : un consommateur, prefetch 10, acquittement manuel.
// La file est déclarée par le broker (ADR 0010, 0026) : le service ne déclare rien et n'a pas le droit configure.
@Configuration
class ConsommateurConfig {

    @Bean
    @ConditionalOnBooleanProperty(name = "app.notification.ecoute", matchIfMissing = true)
    SimpleMessageListenerContainer ecouteAnnoncesPubliees(ConnectionFactory connexions,
            ConsommateurAnnoncePubliee consommateur,
            @Value("${app.notification.file}") String file,
            @Value("${app.notification.prefetch}") int prefetch) {
        SimpleMessageListenerContainer conteneur = new SimpleMessageListenerContainer(connexions);
        conteneur.setQueueNames(file);
        conteneur.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        conteneur.setPrefetchCount(prefetch);
        conteneur.setConcurrentConsumers(1);
        // File absente (broker en cours de chargement des définitions) : le conteneur réessaie au lieu de s'arrêter.
        conteneur.setMissingQueuesFatal(false);
        conteneur.setMessageListener(consommateur);
        return conteneur;
    }
}
