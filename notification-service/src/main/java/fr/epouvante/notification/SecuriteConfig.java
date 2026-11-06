package fr.epouvante.notification;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

// Resource server (docs/cadrage.md §1.2.9, ADR 0003) : signature, iss, aud et exp vérifiés ; rôles du realm → autorités.
@Configuration
class SecuriteConfig {

    @Bean
    SecurityFilterChain filtres(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(regles -> regles
                        // /version reste public : vérification du SHA après déploiement sans compte (ADR 0011).
                        .requestMatchers(HttpMethod.GET, "/api/notifications/version").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Hors /api : port de gestion jamais exposé par un Ingress (ADR 0007), sondes et métriques
                        // sans jeton ; sur le port de l'API, tout autre chemin reste un 404.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(conversion())))
                // CSRF ignoré sur /api/** (US2, ADR 0023 point 2) : l'API n'accepte que des jetons Bearer, sans session
                // ni cookie, il n'y a rien à détourner. Active, la protection répondait 403 à un PUT sans jeton, avant
                // l'authentification, au lieu du 401 attendu (« Gérer ses catégories sans jeton est refusé »).
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    // Construit à la main pour fixer l'URL des clés (adresse interne) et la tolérance d'horloge.
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String cles,
            @Value("${spring.application.name}") String audience,
            @Value("${app.securite.tolerance-horloge}") Duration tolerance) {
        NimbusJwtDecoder decodeur = NimbusJwtDecoder.withJwkSetUri(cles).build();
        decodeur.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(tolerance),
                new JwtIssuerValidator(issuer),
                // Audience ajoutée par un mapper Keycloak : le nom du service.
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience))));
        return decodeur;
    }

    private static JwtAuthenticationConverter conversion() {
        JwtAuthenticationConverter conversion = new JwtAuthenticationConverter();
        conversion.setPrincipalClaimName("preferred_username");
        conversion.setJwtGrantedAuthoritiesConverter(SecuriteConfig::rolesDuRealm);
        return conversion;
    }

    private static Collection<GrantedAuthority> rolesDuRealm(Jwt jwt) {
        Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
        if (realm == null || !(realm.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
