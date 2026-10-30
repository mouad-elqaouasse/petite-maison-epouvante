package fr.epouvante.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Préfixe complet, sans réécriture par Traefik (ADR 0006).
@RestController
class VersionController {

    record Version(String service, String version) {
    }

    private final Version version;

    VersionController(@Value("${spring.application.name}") String service, @Value("${app.version}") String sha) {
        this.version = new Version(service, sha);
    }

    @GetMapping("/api/notifications/version")
    Version version() {
        return version;
    }
}
