package co.edu.unicauca.piedrazul.backend.shared.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SecurityContextExtractor {

    public String currentActorId() {
        return extractActorId(SecurityContextHolder.getContext().getAuthentication());
    }

    public String currentActorRoles() {
        return extractRoles(SecurityContextHolder.getContext().getAuthentication());
    }

    private String extractActorId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        return auth != null ? auth.getName() : "anonymous";
    }

    private static final Set<String> ROLES_TECNICOS_KEYCLOAK = Set.of(
            "offline_access", "uma_authorization", "default-roles-piedrazul"
    );

    @SuppressWarnings("unchecked")
    private String extractRoles(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Map<String, Object> realmAccess = jwtAuth.getToken().getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                return roles.stream()
                        .map(Object::toString)
                        .filter(role -> !ROLES_TECNICOS_KEYCLOAK.contains(role))
                        .toList()
                        .toString();
            }
        }
        return "N/A";
    }
}
