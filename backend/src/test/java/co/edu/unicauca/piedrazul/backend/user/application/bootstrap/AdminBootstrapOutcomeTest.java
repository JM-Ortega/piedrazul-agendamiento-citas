package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato de arranque y despliegue por resultado. Un resultado nuevo debe decidir
 * explícitamente ambas cosas: este test falla si no se agrega a la matriz.
 */
class AdminBootstrapOutcomeTest {

    private static final Set<AdminBootstrapOutcome> SUCCESSFUL =
            EnumSet.of(AdminBootstrapOutcome.CREATED, AdminBootstrapOutcome.ALREADY_LINKED_READY);
    private static final Set<AdminBootstrapOutcome> STARTS_WITHOUT_SUCCESS =
            EnumSet.of(AdminBootstrapOutcome.ALREADY_LINKED_NOT_READY);

    @Test
    void onlyCreatedAndConfirmedReadyAreSuccessful() {
        for (AdminBootstrapOutcome outcome : AdminBootstrapOutcome.values()) {
            assertThat(outcome.successful()).as(outcome.name()).isEqualTo(SUCCESSFUL.contains(outcome));
        }
    }

    @Test
    void everyUnsuccessfulOutcomeAbortsStartupExceptAnAlteredLinkedIdentity() {
        for (AdminBootstrapOutcome outcome : AdminBootstrapOutcome.values()) {
            boolean expected = !SUCCESSFUL.contains(outcome) && !STARTS_WITHOUT_SUCCESS.contains(outcome);
            assertThat(outcome.abortsStartup()).as(outcome.name()).isEqualTo(expected);
        }
    }

    @Test
    void matrixCoversEveryOutcome() {
        assertThat(AdminBootstrapOutcome.values()).hasSize(9);
    }
}
