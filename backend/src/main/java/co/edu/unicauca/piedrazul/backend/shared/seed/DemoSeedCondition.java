package co.edu.unicauca.piedrazul.backend.shared.seed;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;
import java.util.Set;

/**
 * Autoriza los inicializadores de datos de demostración (médicos, pacientes, agendadores,
 * auditor) solo en desarrollo y con opt-in explícito. Se cumple únicamente si:
 * <ul>
 *   <li>{@code app.seed.demo.enabled} vale exactamente {@code true} (sin distinguir
 *   mayúsculas); cualquier otro valor, o su ausencia, lo deja desactivado;</li>
 *   <li>hay perfiles activos y todos son de desarrollo ({@code dev}).</li>
 * </ul>
 * Con {@code prod} activo (también {@code dev,prod} en cualquier orden), sin perfil
 * (default) o con cualquier perfil desconocido, el inicializador no se registra.
 *
 * <p>Desactivarlo no borra ni modifica identidades de demostración ya persistidas: solo
 * evita que se creen.
 */
public class DemoSeedCondition implements Condition {

    public static final String ENABLED_PROPERTY = "app.seed.demo.enabled";

    static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return isAllowed(context.getEnvironment());
    }

    public static boolean isAllowed(Environment environment) {
        boolean optedIn = "true".equalsIgnoreCase(environment.getProperty(ENABLED_PROPERTY, "").trim());
        String[] active = environment.getActiveProfiles();

        return optedIn
                && active.length > 0
                && Arrays.stream(active).allMatch(DEVELOPMENT_PROFILES::contains);
    }
}
