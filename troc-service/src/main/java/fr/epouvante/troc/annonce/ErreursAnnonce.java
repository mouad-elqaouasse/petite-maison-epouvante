package fr.epouvante.troc.annonce;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Réponses 400 de l'API des annonces : ProblemDetail (RFC 9457) avec un message par champ (« erreurs »).
// Sans restriction de contrôleur : une limite d'envoi dépassée est levée avant le choix du contrôleur.
@RestControllerAdvice
class ErreursAnnonce {

    private static final Logger LOG = LoggerFactory.getLogger(ErreursAnnonce.class);

    @ExceptionHandler(AnnonceInvalide.class)
    ProblemDetail annonceInvalide(AnnonceInvalide e) {
        return probleme(e.erreurs());
    }

    // Envoi au-delà de la limite de Spring (spring.servlet.multipart) : refusé avant d'être lu en entier.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail envoiTropLourd() {
        return probleme(Map.of("photo", "La photo dépasse 2 Mo : choisissez un fichier plus léger."));
    }

    // Hébergeur des photos en erreur ou trop lent : 502, sans rien de sa réponse (StockageIndisponible n'en contient
    // pas). Seul le code HTTP ou le type d'erreur est journalisé.
    @ExceptionHandler(StockagePhoto.StockageIndisponible.class)
    ProblemDetail stockageIndisponible(StockagePhoto.StockageIndisponible e) {
        LOG.warn("Publication refusée, stockage des photos indisponible : {}", e.getMessage());
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "La photo n’a pas pu être enregistrée. Réessayez dans un instant.");
        probleme.setTitle("Stockage des photos indisponible");
        return probleme;
    }

    private static ProblemDetail probleme(Map<String, String> erreurs) {
        ProblemDetail probleme = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        probleme.setTitle("Annonce invalide");
        probleme.setProperty("erreurs", erreurs);
        return probleme;
    }
}
