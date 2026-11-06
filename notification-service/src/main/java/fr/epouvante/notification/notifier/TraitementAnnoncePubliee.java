package fr.epouvante.notification.notifier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.epouvante.notification.abonnement.AbonnementDepot;
import fr.epouvante.notification.abonnement.Categorie;
import io.micrometer.core.instrument.MeterRegistry;

// Notifications d'une annonce publiée (US3), écrites dans une seule transaction : toutes ou aucune.
// L'auteur reçoit CONFIRMATION ; chaque abonné à la catégorie reçoit ALERTE, sauf l'auteur (ADR 0025, point 5).
// Destinataires calculés à la consommation, d'après les abonnements du moment.
@Service
class TraitementAnnoncePubliee {

    private final AbonnementDepot abonnements;
    private final NotificationDepot notifications;
    private final MeterRegistry metriques;

    TraitementAnnoncePubliee(AbonnementDepot abonnements, NotificationDepot notifications, MeterRegistry metriques) {
        this.abonnements = abonnements;
        this.notifications = notifications;
        this.metriques = metriques;
    }

    // Commit au retour de la méthode : le consommateur acquitte ensuite (ack après commit, docs/cadrage.md §1.2.8).
    @Transactional
    public int traiter(AnnoncePubliee evenement) {
        Categorie categorie = evenement.verifier();
        int creees = creer(evenement.auteurId(), evenement, TypeNotification.CONFIRMATION);
        for (String membre : abonnements.membresAbonnes(categorie)) {
            if (!membre.equals(evenement.auteurId())) {
                creees += creer(membre, evenement, TypeNotification.ALERTE);
            }
        }
        return creees;
    }

    private int creer(String membre, AnnoncePubliee evenement, TypeNotification type) {
        if (notifications.creer(membre, evenement.annonceId(), type, evenement.titre())) {
            metriques.counter("notifications.creees", "type", type.name()).increment();
            return 1;
        }
        return 0;
    }
}
