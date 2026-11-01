package co.edu.unicauca.piedrazul.backend.shared.audit;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Determina quién ejecuta una acción, para la auditoría. Nunca devuelve un actor "anónimo":
 * <ul>
 *   <li>Con un usuario autenticado, es ese usuario (id de su cuenta de Keycloak y sus roles).</li>
 *   <li>En una petición pública, sin usuario autenticado (registro de un paciente, vincular una
 *       cuenta), quien actúa es la propia cuenta afectada: nadie más interviene. Ver
 *       {@link #currentActorId(String)}.</li>
 *   <li>Sin petición (el arranque de la aplicación, sus datos de prueba), actúa el sistema:
 *       {@value #SYSTEM_ACTOR_ID}.</li>
 * </ul>
 * Un {@link AnonymousAuthenticationToken} cuenta como "sin usuario": Spring Security lo pone
 * en el contexto de las peticiones públicas y su nombre es "anonymousUser".
 */
@Component
public class SecurityContextExtractor {

    public static final String SYSTEM_ACTOR_ID = "system";
    static final String SYSTEM_ACTOR_ROLES = "[SYSTEM]";
    static final String NO_ROLES = "N/A";

    private static final Set<String> ROLES_TECNICOS_KEYCLOAK = Set.of(
            "offline_access", "uma_authorization", "default-roles-piedrazul"
    );

    /**
     * El usuario autenticado, o el sistema si no lo hay. Para código que solo se ejecuta con un
     * usuario autenticado; si puede ejecutarse en un flujo público, usar {@link #currentActorId(String)}.
     */
    public String currentActorId() {
        Authentication auth = authenticated();
        return auth != null ? extractActorId(auth) : SYSTEM_ACTOR_ID;
    }

    public String currentActorRoles() {
        Authentication auth = authenticated();
        return auth != null ? extractRoles(auth) : SYSTEM_ACTOR_ROLES;
    }

    /**
     * Quien actúa sobre la cuenta {@code affectedAccountId}: el usuario autenticado; si no lo hay
     * pero hay una petición (flujo público), la propia cuenta afectada; y si tampoco hay petición,
     * el sistema.
     */
    public String currentActorId(String affectedAccountId) {
        Authentication auth = authenticated();
        if (auth != null) {
            return extractActorId(auth);
        }
        return actsAsTheAffectedAccount(affectedAccountId) ? affectedAccountId : SYSTEM_ACTOR_ID;
    }

    /** Los roles de quien determina {@link #currentActorId(String)}. */
    public String currentActorRoles(String affectedAccountId) {
        Authentication auth = authenticated();
        if (auth != null) {
            return extractRoles(auth);
        }
        return actsAsTheAffectedAccount(affectedAccountId) ? NO_ROLES : SYSTEM_ACTOR_ROLES;
    }

    private static boolean actsAsTheAffectedAccount(String affectedAccountId) {
        return affectedAccountId != null
                && !affectedAccountId.isBlank()
                && RequestContextHolder.getRequestAttributes() != null;
    }

    /** La autenticación de un usuario real, o {@code null}. */
    private static Authentication authenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return null;
        }
        return auth;
    }

    private static String extractActorId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken().getSubject() != null) {
            return jwtAuth.getToken().getSubject();
        }
        // Un token sin "sub" (los tokens ligeros de Keycloak): el nombre que resolvió el conversor.
        return auth.getName();
    }

    @SuppressWarnings("unchecked")
    private static String extractRoles(Authentication auth) {
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
        return NO_ROLES;
    }
}
