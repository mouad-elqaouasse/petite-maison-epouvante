package fr.epouvante.troc.annonce;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// API des annonces (US1a). Toutes les routes exigent un jeton (SecuriteConfig : /api/** authentifié).
@RestController
@RequestMapping("/api/troc")
class AnnonceController {

    // Vue renvoyée au front : catégorie par son libellé, comme dans GET /categories.
    record AnnonceVue(UUID id, String titre, String description, String categorie, Annonce.Type type,
            Annonce.Statut statut, String auteurId, String photoUrl, Instant creeLe) {

        static AnnonceVue de(Annonce a) {
            return new AnnonceVue(a.id(), a.titre(), a.description(), a.categorie().libelle(), a.type(), a.statut(),
                    a.auteurId(), a.photoUrl(), a.creeLe());
        }
    }

    private final FormulaireAnnonce formulaire;
    private final PublicationAnnonce publication;
    private final AnnonceDepot depot;

    AnnonceController(FormulaireAnnonce formulaire, PublicationAnnonce publication, AnnonceDepot depot) {
        this.formulaire = formulaire;
        this.publication = publication;
        this.depot = depot;
    }

    @GetMapping("/categories")
    List<String> categories() {
        return Arrays.stream(Categorie.values()).map(Categorie::libelle).toList();
    }

    @GetMapping("/annonces")
    List<AnnonceVue> annonces() {
        return depot.publiees().stream().map(AnnonceVue::de).toList();
    }

    // Champs facultatifs ici : FormulaireAnnonce relève toutes les erreurs ensemble (un 400, un message par champ).
    // L'auteur est le sub du jeton, jamais un champ de la requête (docs/cadrage.md §1.1 point 2).
    @PostMapping(path = "/annonces", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AnnonceVue> publier(
            @RequestParam(required = false) String titre,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String categorie,
            @RequestParam(required = false) String type,
            @RequestParam(name = "photo", required = false) List<MultipartFile> photos,
            JwtAuthenticationToken jeton) {
        FormulaireAnnonce.Demande demande = formulaire.lire(titre, description, categorie, type, photos);
        Annonce annonce = publication.publier(demande, jeton.getToken().getSubject());
        return ResponseEntity.created(URI.create("/api/troc/annonces/" + annonce.id())).body(AnnonceVue.de(annonce));
    }
}
