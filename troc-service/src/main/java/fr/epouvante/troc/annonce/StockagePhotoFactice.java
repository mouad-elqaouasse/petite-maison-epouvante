package fr.epouvante.troc.annonce;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Fausse implémentation de StockagePhoto, pour les tests et les campagnes de charge seulement (profil photo-factice,
// jamais actif par défaut) : ne garde aucun octet, renvoie une URL qui ne mène nulle part (domaine réservé .invalid).
// Base des URL réglable (SQ-14, D4) : l'E2E la règle sur res.cloudinary.com, seul hôte d'images autorisé par la CSP du
// front, et sert ces URL par une route Playwright.
// Compte les photos reçues, pour vérifier qu'une photo invalide n'est jamais stockée.
@Component
@Profile("photo-factice")
class StockagePhotoFactice implements StockagePhoto {

    private final AtomicInteger photosStockees = new AtomicInteger();
    private final String urlBase;

    StockagePhotoFactice(@Value("${app.photo-factice.url-base:https://photos.invalid/}") String urlBase) {
        this.urlBase = urlBase;
    }

    @Override
    public PhotoStockee stocker(PhotoVerifiee photo) {
        photosStockees.incrementAndGet();
        String publicId = "factice/" + UUID.randomUUID();
        return new PhotoStockee(publicId, urlBase + publicId);
    }

    @Override
    public void supprimer(String publicId) {
        // Rien n'est gardé : rien à supprimer.
    }

    int photosStockees() {
        return photosStockees.get();
    }
}
