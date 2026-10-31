package co.edu.unicauca.piedrazul.backend.shared.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quién actúa, para la auditoría. Ninguna combinación puede producir un actor "anónimo".
 */
class SecurityContextExtractorTest {

    private static final String ACCOUNT = "9b8f2d64-1c3e-4f0a-a7d5-5e6b8c9d0e12";

    private final SecurityContextExtractor extractor = new SecurityContextExtractor();

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void inAWebRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static void loggedInAs(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject(subject)
                .claim("preferred_username", "11000001")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "11000001"));
    }

    private static void asAnonymousVisitor() {
        // Es lo que Spring Security pone en el contexto de una petición pública.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }

    // ---- usuario autenticado ---------------------------------------------------

    @Test
    void anAuthenticatedUserIsTheActorWithTheirAccountIdAndRoles() {
        inAWebRequest();
        loggedInAs("kc-clara", "offline_access", "ADMIN", "DOCTOR", "default-roles-piedrazul");

        assertThat(extractor.currentActorId()).isEqualTo("kc-clara");
        assertThat(extractor.currentActorRoles()).isEqualTo("[ADMIN, DOCTOR]");
    }

    @Test
    void anAuthenticatedUserIsTheActorEvenWhenTheyActOnAnotherAccount() {
        inAWebRequest();
        loggedInAs("kc-clara", "ADMIN");

        assertThat(extractor.currentActorId(ACCOUNT)).isEqualTo("kc-clara");
        assertThat(extractor.currentActorRoles(ACCOUNT)).isEqualTo("[ADMIN]");
    }

    @Test
    void aTokenWithoutSubjectStillYieldsAnActorInsteadOfAnAnonymousOne() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("scope", "email").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), "11000001"));

        assertThat(extractor.currentActorId()).isEqualTo("11000001");
    }

    // ---- petición pública: la propia cuenta ------------------------------------

    @Test
    void inAPublicRequestTheAffectedAccountIsTheActor() {
        inAWebRequest();
        asAnonymousVisitor();

        assertThat(extractor.currentActorId(ACCOUNT)).isEqualTo(ACCOUNT);
        assertThat(extractor.currentActorRoles(ACCOUNT)).isEqualTo("N/A");
    }

    @Test
    void aPublicRequestWithAnEmptySecurityContextIsTreatedTheSame() {
        inAWebRequest();

        assertThat(extractor.currentActorId(ACCOUNT)).isEqualTo(ACCOUNT);
    }

    @Test
    void theAnonymousTokenIsNeverReportedAsAnActor() {
        inAWebRequest();
        asAnonymousVisitor();

        assertThat(extractor.currentActorId(ACCOUNT)).isNotEqualTo("anonymousUser");
        assertThat(extractor.currentActorId()).isNotEqualTo("anonymousUser");
    }

    // ---- sin petición: el sistema ----------------------------------------------

    @Test
    void withoutARequestTheSystemIsTheActor() {
        assertThat(extractor.currentActorId(ACCOUNT)).isEqualTo("system");
        assertThat(extractor.currentActorRoles(ACCOUNT)).isEqualTo("[SYSTEM]");
    }

    @Test
    void withoutARequestTheSystemActsEvenIfTheAnonymousTokenIsPresent() {
        asAnonymousVisitor();

        assertThat(extractor.currentActorId(ACCOUNT)).isEqualTo("system");
    }

    @Test
    void whenThereIsNoAffectedAccountTheSystemIsTheFallbackNotAnAnonymousActor() {
        inAWebRequest();

        assertThat(extractor.currentActorId((String) null)).isEqualTo("system");
        assertThat(extractor.currentActorId("  ")).isEqualTo("system");
        assertThat(extractor.currentActorId()).isEqualTo("system");
        assertThat(extractor.currentActorRoles()).isEqualTo("[SYSTEM]");
    }

    // ---- ninguna combinación produce un actor anónimo --------------------------

    @Test
    void noCombinationEverProducesAnAnonymousActor() {
        Runnable[] situations = {
                () -> { },                                              // nada
                SecurityContextExtractorTest::inAWebRequest,             // petición sin usuario
                SecurityContextExtractorTest::asAnonymousVisitor,        // visitante anónimo
                () -> SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p")),
        };

        for (Runnable situation : situations) {
            situation.run();

            for (String actor : new String[]{extractor.currentActorId(), extractor.currentActorId(ACCOUNT),
                    extractor.currentActorId((String) null)}) {
                assertThat(actor).isNotNull().isNotBlank();
                assertThat(actor.toLowerCase()).doesNotContain("anonymous");
            }
        }
    }
}
