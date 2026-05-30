// src/test/java/co/edu/unicauca/piedrazul/backend/appointment/integration/AppointmentRepositoryIntegrationTest.java
package co.edu.unicauca.piedrazul.backend.appointment.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.appointment.integration.config.AppointmentJpaTestConfig;
import co.edu.unicauca.piedrazul.backend.appointment.util.AppointmentTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(AppointmentJpaTestConfig.class)  // ← clave: importa solo lo que necesitamos
class AppointmentRepositoryIntegrationTest {

    @Autowired
    private AppointmentJpaRepository jpaRepository;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
    }

    @Test
    void deberiaGuardarYRecuperarCitaPorId() {
        AppointmentEntity entity = AppointmentTestFactory.buildEntity();

        AppointmentEntity saved = jpaRepository.save(entity);
        Optional<AppointmentEntity> found = jpaRepository.findById(saved.getIdAppointment());

        assertThat(found).isPresent();
        assertThat(found.get().getDoctorName()).isEqualTo("Dr. Juan Pérez");
        assertThat(found.get().getSpecialty()).isEqualTo(Specialty.FISIOTERAPIA);
        assertThat(found.get().getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    @Test
    void deberiaBuscarCitasPorIdDoctor() {
        jpaRepository.save(AppointmentTestFactory.buildEntity());

        List<AppointmentEntity> result = jpaRepository
                .findByIdDoctor(AppointmentTestFactory.DOCTOR_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIdDoctor())
                .isEqualTo(AppointmentTestFactory.DOCTOR_ID);
    }

    @Test
    void deberiaBuscarCitasPorIdPaciente() {
        jpaRepository.save(AppointmentTestFactory.buildEntity());

        List<AppointmentEntity> result = jpaRepository
                .findByIdPatient(AppointmentTestFactory.PATIENT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIdPatient())
                .isEqualTo(AppointmentTestFactory.PATIENT_ID);
    }

    @Test
    void deberiaBuscarCitasPorFecha() {
        jpaRepository.save(AppointmentTestFactory.buildEntity());

        List<AppointmentEntity> result = jpaRepository
                .findByDate(AppointmentTestFactory.FUTURE_DATE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDate())
                .isEqualTo(AppointmentTestFactory.FUTURE_DATE);
    }

    @Test
    void deberiaBuscarCitasPorDoctorYFecha() {
        jpaRepository.save(AppointmentTestFactory.buildEntity());

        List<AppointmentEntity> result = jpaRepository.findByIdDoctorAndDate(
                AppointmentTestFactory.DOCTOR_ID,
                AppointmentTestFactory.FUTURE_DATE);

        assertThat(result).hasSize(1);
    }

    @Test
    void deberiaRetornarListaVaciaSiNingunaCoincide() {
        jpaRepository.save(AppointmentTestFactory.buildEntity());

        List<AppointmentEntity> result = jpaRepository
                .findByIdDoctor(UUID.randomUUID());

        assertThat(result).isEmpty();
    }
}