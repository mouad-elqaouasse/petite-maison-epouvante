package fr.epouvante.troc;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// ADR 0011 : l'utilisateur et ses rôles tels que vus par le service, seulement pour le propriétaire du jeton.
@RestController
class MoiController {

    record Moi(String sub, String nom, List<String> roles) {
    }

    @GetMapping("/api/troc/moi")
    Moi moi(JwtAuthenticationToken jeton) {
        List<String> roles = jeton.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(autorite -> autorite.startsWith("ROLE_"))
                .map(autorite -> autorite.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new Moi(jeton.getToken().getSubject(), jeton.getName(), roles);
    }
}
