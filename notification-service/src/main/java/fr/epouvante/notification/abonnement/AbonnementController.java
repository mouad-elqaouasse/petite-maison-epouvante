package fr.epouvante.notification.abonnement;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// US2 — catégories d'intérêt du membre connecté (docs/backlog/US2-gerer-centres-interet.md, décisions D2 et D3).
// Le membre est le sub du jeton, jamais un paramètre de la requête (docs/cadrage.md §1.1 point 2).
@RestController
@RequestMapping("/api/notifications/abonnements")
class AbonnementController {

    private final AbonnementDepot depot;

    AbonnementController(AbonnementDepot depot) {
        this.depot = depot;
    }

    @GetMapping
    List<String> abonnements(JwtAuthenticationToken jeton) {
        return depot.categories(membre(jeton)).stream().map(Categorie::libelle).toList();
    }

    @PutMapping("/{categorie}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void abonner(JwtAuthenticationToken jeton, @PathVariable String categorie) {
        depot.ajouter(membre(jeton), connue(categorie));
    }

    @DeleteMapping("/{categorie}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void desabonner(JwtAuthenticationToken jeton, @PathVariable String categorie) {
        depot.retirer(membre(jeton), connue(categorie));
    }

    @ExceptionHandler(CategorieInconnue.class)
    ProblemDetail categorieInconnue() {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Cette catégorie ne fait pas partie de la liste.");
        probleme.setTitle("Catégorie inconnue");
        return probleme;
    }

    private static String membre(JwtAuthenticationToken jeton) {
        return jeton.getToken().getSubject();
    }

    private static Categorie connue(String libelle) {
        return Categorie.depuisLibelle(libelle).orElseThrow(CategorieInconnue::new);
    }

    static final class CategorieInconnue extends RuntimeException {
        CategorieInconnue() {
            super(null, null, false, false);
        }
    }
}
