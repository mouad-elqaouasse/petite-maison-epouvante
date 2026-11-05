package fr.epouvante.troc.annonce;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Fausse implémentation de StockagePhoto, pour les tests et les campagnes de charge seulement (profil photo-factice,
// jamais actif par défaut) : ne garde aucun octet, renvoie une URL qui ne mène nulle part (domaine réservé .invalid).
// Compte les photos reçues, pour vérifier qu'une photo invalide n'est jamais stockée.
@Component
@Profile("photo-factice")
class StockagePhotoFactice implements StockagePhoto {

    private final AtomicInteger photosStockees = new AtomicInteger();

    @Override
    public PhotoStockee stocker(PhotoVerifiee photo) {
        photosStockees.incrementAndGet();
        String publicId = "factice/" + UUID.randomUUID();
        return new PhotoStockee(publicId, "https://photos.invalid/" + publicId);
    }

    @Override
    public void supprimer(String publicId) {
        // Rien n'est gardé : rien à supprimer.
    }

    int photosStockees() {
        return photosStockees.get();
    }
}
