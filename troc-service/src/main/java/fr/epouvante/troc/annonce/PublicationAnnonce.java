package fr.epouvante.troc.annonce;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import fr.epouvante.troc.annonce.StockagePhoto.PhotoStockee;

// Publication d'une annonce déjà contrôlée (US1a) : photo stockée (hors transaction : environ 1,5 s chez Cloudinary,
// sans garder de connexion à la base), puis annonce enregistrée au statut PUBLIEE et AnnoncePubliee émis après le
// commit. Pas de photo orpheline : si l'enregistrement échoue, la photo est supprimée (docs/cadrage.md §1.1 point 3).
@Service
class PublicationAnnonce {

    private static final Logger LOG = LoggerFactory.getLogger(PublicationAnnonce.class);

    private final StockagePhoto stockage;
    private final AnnonceDepot depot;
    private final ApplicationEventPublisher evenements;
    private final TransactionOperations transactions;

    PublicationAnnonce(StockagePhoto stockage, AnnonceDepot depot, ApplicationEventPublisher evenements,
            TransactionOperations transactions) {
        this.stockage = stockage;
        this.depot = depot;
        this.evenements = evenements;
        this.transactions = transactions;
    }

    Annonce publier(FormulaireAnnonce.Demande demande, String auteurId) {
        PhotoStockee photo = stockage.stocker(demande.photo());
        // Précision de PostgreSQL (microseconde) : la date renvoyée est celle qui sera relue.
        Annonce annonce = new Annonce(UUID.randomUUID(), demande.titre(), demande.description(), demande.categorie(),
                demande.type(), Annonce.Statut.PUBLIEE, auteurId, photo.publicId(), photo.url(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        String correlationId = UUID.randomUUID().toString();
        try {
            transactions.executeWithoutResult(statut -> {
                depot.enregistrer(annonce);
                evenements.publishEvent(new EmissionAnnoncePubliee.AnnonceEnregistree(annonce, correlationId));
            });
        } catch (RuntimeException echec) {
            supprimerSansMasquer(photo.publicId(), echec);
            throw echec;
        }
        return annonce;
    }

    // L'erreur d'enregistrement reste celle renvoyée ; un échec de la suppression lui est attaché et journalisé.
    private void supprimerSansMasquer(String publicId, RuntimeException echec) {
        try {
            stockage.supprimer(publicId);
        } catch (RuntimeException suppression) {
            echec.addSuppressed(suppression);
            LOG.error("Photo orpheline {} : suppression impossible ({})", publicId, suppression.getMessage());
        }
    }
}
