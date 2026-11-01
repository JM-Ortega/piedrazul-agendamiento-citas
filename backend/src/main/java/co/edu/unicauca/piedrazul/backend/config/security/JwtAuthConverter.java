package co.edu.unicauca.piedrazul.backend.config.security;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";

    private static final Set<String> BUSINESS_ROLES =
            Arrays.stream(Role.values())
                    .map(Enum::name)
                    .collect(Collectors.toSet());

    private final JwtGrantedAuthoritiesConverter defaultConverter =
            new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> defaultAuthorities = defaultConverter.convert(jwt);
        if (defaultAuthorities == null) {
            defaultAuthorities = Set.of();
        }

        Collection<GrantedAuthority> authorities = Stream.concat(
                defaultAuthorities.stream(),
                extractRealmRoles(jwt).stream()
        ).collect(Collectors.toSet());

        String principalName = jwt.getClaimAsString("preferred_username");
        if (principalName == null || principalName.isBlank()) {
            principalName = jwt.getSubject();
        }

        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return Set.of();
        }

        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof Collection<?> roles)) {
            return Set.of();
        }

        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(BUSINESS_ROLES::contains)
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toSet());
    }
}