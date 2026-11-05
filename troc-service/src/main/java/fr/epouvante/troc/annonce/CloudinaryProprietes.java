package fr.epouvante.troc.annonce;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Configuration de l'adaptateur Cloudinary. Sur le cluster, cloud-name, api-key et api-secret viennent du Secret
// Kubernetes cloudinary (variables APP_CLOUDINARY_*, deploy/scripts/cloudinary-secrets.sh) ; jamais dans le dépôt.
@ConfigurationProperties("app.cloudinary")
record CloudinaryProprietes(
        @DefaultValue("https://api.cloudinary.com") String url,
        String cloudName,
        String apiKey,
        String apiSecret,
        // Dossier dynamique (asset_folder) des photos d'annonces (exp. 4).
        @DefaultValue("epouvante") String dossier,
        // Un envoi prend environ 1,5 s depuis le VPS (exp. 4, §5) : marge large, sans bloquer un fil trop longtemps.
        @DefaultValue("2s") Duration delaiConnexion,
        @DefaultValue("10s") Duration delaiLecture) {
}
