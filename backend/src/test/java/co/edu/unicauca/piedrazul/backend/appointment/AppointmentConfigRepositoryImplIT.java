package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentConfigJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentConfigRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentConfigEntity;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La fila de configuración (id=1) viene sembrada por la migración baseline con
 * autonomous_scheduling_enabled=true — por eso los casos de "fila ausente" borran la fila
 * primero, para poder ejercitar de verdad el fallback/la excepción del código actual.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AppointmentConfigRepositoryImplIT.TestBeans.class)
class AppointmentConfigRepositoryImplIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestBeans {
        @Bean
        AppointmentConfigRepository appointmentConfigRepository(AppointmentConfigJpaRepository jpaRepository) {
            return new AppointmentConfigRepositoryImpl(jpaRepository);
        }
    }

    @Autowired
    private AppointmentConfigRepository appointmentConfigRepository;
    @Autowired
    private AppointmentConfigJpaRepository jpaRepository;

    @AfterEach
    void restoreSeedRow() {
        // La fila es un singleton compartido con el resto de la suite (mismo contenedor
        // Postgres) — se restaura al estado sembrado por la migración tras cada test, incluso
        // si el test la borró (por eso se recrea directo por JPA, no vía el puerto bajo prueba).
        AppointmentConfigEntity entity = jpaRepository.findById(1).orElseGet(AppointmentConfigEntity::new);
        entity.setId(1);
        entity.setAutonomousSchedulingEnabled(true);
        jpaRepository.save(entity);
    }

    @Test
    void isAutonomousSchedulingEnabledShouldReflectFalseWhenRowSaysSo() {
        appointmentConfigRepository.setAutonomousSchedulingEnabled(false);

        assertThat(appointmentConfigRepository.isAutonomousSchedulingEnabled()).isFalse();
    }

    @Test
    void isAutonomousSchedulingEnabledShouldReflectTrueWhenRowSaysSo() {
        appointmentConfigRepository.setAutonomousSchedulingEnabled(false);
        appointmentConfigRepository.setAutonomousSchedulingEnabled(true);

        assertThat(appointmentConfigRepository.isAutonomousSchedulingEnabled()).isTrue();
    }

    @Test
    void isAutonomousSchedulingEnabledShouldDefaultToTrueWhenRowIsAbsent() {
        jpaRepository.deleteById(1);

        assertThat(appointmentConfigRepository.isAutonomousSchedulingEnabled()).isTrue();
    }

    @Test
    void setAutonomousSchedulingEnabledShouldThrowWhenRowIsAbsent() {
        jpaRepository.deleteById(1);

        assertThatThrownBy(() -> appointmentConfigRepository.setAutonomousSchedulingEnabled(false))
                .isInstanceOf(IllegalStateException.class);
    }
}
