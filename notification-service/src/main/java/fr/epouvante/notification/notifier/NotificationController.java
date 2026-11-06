package fr.epouvante.notification.notifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// US3 — lecture minimale des notifications du membre connecté (notes d'US3 ; US4 en complète les règles).
// Le membre est le sub du jeton, jamais un paramètre de la requête (docs/cadrage.md §1.1 point 2).
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    record NotificationVue(String type, String texte, UUID annonceId, String titreAnnonce, Instant creeLe) {
    }

    private final NotificationDepot depot;

    NotificationController(NotificationDepot depot) {
        this.depot = depot;
    }

    @GetMapping
    List<NotificationVue> notifications(JwtAuthenticationToken jeton) {
        return depot.duMembre(jeton.getToken().getSubject()).stream()
                .map(n -> new NotificationVue(n.type().name(), n.type().texte(), n.annonceId(), n.titreAnnonce(),
                        n.creeLe()))
                .toList();
    }
}
