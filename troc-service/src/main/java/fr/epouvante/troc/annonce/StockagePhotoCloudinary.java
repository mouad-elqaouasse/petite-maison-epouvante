package fr.epouvante.troc.annonce;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// Implémentation de production de StockagePhoto : API d'upload de Cloudinary, appelée par RestClient sans le SDK
// (décision D5 du 05/11/2026). Upload et destroy signés comme à l'expérience 4 (SHA-1, algorithme par défaut de
// Cloudinary). Les réponses d'erreur de Cloudinary ne sont jamais lues, renvoyées ni journalisées : seul le code HTTP
// sort de l'adaptateur (le message « Invalid api_key » contient la clé envoyée, exp. 4 §7).
@Component
@Profile("!photo-factice")
@EnableConfigurationProperties(CloudinaryProprietes.class)
class StockagePhotoCloudinary implements StockagePhoto {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final CloudinaryProprietes proprietes;
    private final Clock horloge;
    private final RestClient client;

    @Autowired
    StockagePhotoCloudinary(CloudinaryProprietes proprietes) {
        this(proprietes, Clock.systemUTC());
    }

    StockagePhotoCloudinary(CloudinaryProprietes proprietes, Clock horloge) {
        exiger("cloud-name", proprietes.cloudName());
        exiger("api-key", proprietes.apiKey());
        exiger("api-secret", proprietes.apiSecret());
        this.proprietes = proprietes;
        this.horloge = horloge;
        JdkClientHttpRequestFactory fabrique = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(proprietes.delaiConnexion())
                        // HTTP/1.1 : sans tentative de passage en HTTP/2, qui échoue avec un corps de requête sur
                        // une connexion en clair (API simulée des tests) ; un envoi par photo, pas de gain attendu.
                        .version(HttpClient.Version.HTTP_1_1)
                        .build());
        fabrique.setReadTimeout(proprietes.delaiLecture());
        this.client = RestClient.builder()
                .baseUrl(proprietes.url() + "/v1_1/" + proprietes.cloudName() + "/image")
                .requestFactory(fabrique)
                .defaultStatusHandler(HttpStatusCode::isError, (requete, reponse) -> {
                    throw new StockageIndisponible("Cloudinary a répondu " + reponse.getStatusCode().value());
                })
                .build();
    }

    // Nom de la propriété manquante seulement, jamais une valeur.
    private static void exiger(String nom, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalStateException("Configuration Cloudinary incomplète : app.cloudinary." + nom + " manquant");
        }
    }

    @Override
    public PhotoStockee stocker(PhotoVerifiee photo) {
        MultiValueMap<String, Object> corps = signer(Map.of("asset_folder", proprietes.dossier()));
        corps.add("file", new ByteArrayResource(photo.octets()) {
            @Override
            public String getFilename() {
                return "photo";
            }
        });
        Map<String, Object> reponse = appeler("/upload", MediaType.MULTIPART_FORM_DATA, corps);
        if (reponse == null || !(reponse.get("public_id") instanceof String publicId)
                || !(reponse.get("secure_url") instanceof String url)) {
            throw new StockageIndisponible("Cloudinary a renvoyé une réponse incomplète");
        }
        return new PhotoStockee(publicId, url);
    }

    // invalidate=true : l'URL publique cesse de servir la photo tout de suite (exp. 4, §5).
    @Override
    public void supprimer(String publicId) {
        appeler("/destroy", MediaType.APPLICATION_FORM_URLENCODED,
                signer(Map.of("public_id", publicId, "invalidate", "true")));
    }

    private Map<String, Object> appeler(String chemin, MediaType type, MultiValueMap<String, Object> corps) {
        try {
            return client.post().uri(chemin).contentType(type).body(corps).retrieve().body(JSON);
        } catch (StockageIndisponible e) {
            throw e;
        } catch (RestClientException e) {
            // Délai dépassé, connexion refusée… Le message d'origine contient l'URL appelée : seul le type sort.
            throw new StockageIndisponible("Cloudinary injoignable ou trop lent (" + e.getClass().getSimpleName() + ")");
        }
    }

    // Signature Cloudinary : paramètres triés « clé=valeur » joints par &, suivis du secret, condensés en SHA-1.
    private MultiValueMap<String, Object> signer(Map<String, String> parametres) {
        SortedMap<String, String> signes = new TreeMap<>(parametres);
        signes.put("timestamp", String.valueOf(horloge.instant().getEpochSecond()));
        String aSigner = signes.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&")) + proprietes.apiSecret();

        MultiValueMap<String, Object> corps = new LinkedMultiValueMap<>();
        signes.forEach(corps::add);
        corps.add("api_key", proprietes.apiKey());
        corps.add("signature", sha1(aSigner));
        return corps;
    }

    // SHA-1 imposé par le protocole de signature de Cloudinary (valeur par défaut, validée à l'exp. 4) ; SHA-256
    // possible mais non testé (exp. 4, §7). Ce n'est pas un stockage de mot de passe.
    @SuppressWarnings("java:S4790")
    private static String sha1(String texte) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(texte.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
