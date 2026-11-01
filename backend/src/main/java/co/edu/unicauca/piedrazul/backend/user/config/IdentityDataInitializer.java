package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapRequest;
import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapResult;
import co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Bootstrap del primer administrador de negocio al arrancar, solo si
 * {@code IDENTITY_SEED_ENABLED=true}. Deshabilitado (por defecto) no hace ninguna
 * operación de Keycloak ni de Person y no necesita la contraseña inicial.
 *
 * <p>Emite una única línea {@code identity-bootstrap result=...} (sin datos personales ni
 * credenciales), que el despliegue lee. Fail-closed: con un resultado que no es un bootstrap
 * listo, el arranque falla (y la política de reinicio del contenedor lo reintenta), salvo
 * {@code ALREADY_LINKED_NOT_READY}, que arranca sin reparar nada y que el despliegue trata
 * como fallo. Ver {@link AdminBootstrapOutcome} y
 * docs/operations/runbooks/primer-admin-negocio.md.
 *
 * <p>Corre antes que los inicializadores de demostración (orden 1): en desarrollo, los
 * médicos de demostración traen ADMIN y bloquearían una creación posterior.
 *
 * <p>Los datos de demostración (agendadores, auditor, médicos, pacientes) no dependen de
 * esta propiedad: ver {@code DemoStaffDataInitializer} y {@code DemoSeedCondition}.
 */
@Component
@EnableConfigurationProperties(IdentitySeedProperties.class)
public class IdentityDataInitializer implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdentityDataInitializer.class);

    private final IdentitySeedProperties properties;
    private final AdminBootstrapService adminBootstrapService;

    public IdentityDataInitializer(IdentitySeedProperties properties, AdminBootstrapService adminBootstrapService) {
        this.properties = properties;
        this.adminBootstrapService = adminBootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        IdentitySeedProperties.SeedUser admin = properties.getAdmin();
        AdminBootstrapResult result = adminBootstrapService.bootstrap(new AdminBootstrapRequest(
                admin.getUsername(),
                admin.getIdentificationType(),
                admin.getFirstName(),
                admin.getLastName(),
                admin.getEmail(),
                admin.getPhone(),
                admin.getPassword()));

        String line = result.toLogLine();
        if (result.outcome().successful()) {
            log.info(line);
            return;
        }
        if (!result.outcome().abortsStartup()) {
            log.warn(line);
            return;
        }

        log.error(line);
        // Fail-closed: el backend no arranca con un bootstrap habilitado que no terminó bien.
        // El mensaje es la misma línea, sin datos personales ni credenciales.
        throw new IllegalStateException("Bootstrap del primer administrador no completado: " + line);
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
