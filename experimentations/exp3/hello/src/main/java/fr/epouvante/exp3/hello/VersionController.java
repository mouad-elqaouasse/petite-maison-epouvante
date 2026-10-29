package fr.epouvante.exp3.hello;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
class VersionController {

    record Version(String sha, boolean secretPresent) {
    }

    private final String sha;
    // Seule la présence du secret est exposée, jamais sa valeur.
    private final boolean secretPresent;

    VersionController(@Value("${app.sha}") String sha, @Value("${app.secret:}") String secret) {
        this.sha = sha;
        this.secretPresent = !secret.isBlank();
    }

    @GetMapping("/version")
    Version version() {
        return new Version(sha, secretPresent);
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    String hello() {
        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><title>Hello</title></head>"
                + "<body><h1>Hello depuis la Petite Maison de l'Épouvante, livré par Flux</h1>"
                + "<p>Version : <code>" + HtmlUtils.htmlEscape(sha) + "</code></p></body></html>";
    }
}
