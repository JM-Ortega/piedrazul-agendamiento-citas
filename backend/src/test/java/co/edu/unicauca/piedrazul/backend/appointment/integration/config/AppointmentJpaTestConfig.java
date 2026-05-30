// src/test/java/co/edu/unicauca/piedrazul/backend/appointment/integration/config/AppointmentJpaTestConfig.java
package co.edu.unicauca.piedrazul.backend.appointment.integration.config;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class AppointmentJpaTestConfig {

    // Mockeamos PatientConsultPort porque AppointmentMapper lo necesita
    // pero en el test de repositorio no nos importa su comportamiento
    @Bean
    public PatientConsultPort patientConsultPort() {
        return mock(PatientConsultPort.class);
    }

    @Bean
    public AppointmentMapper appointmentMapper(PatientConsultPort patientConsultPort) {
        return new AppointmentMapper(patientConsultPort);
    }

    @Bean
    public AppointmentRepositoryImpl appointmentRepositoryImpl(
            AppointmentJpaRepository jpaRepository,
            AppointmentMapper appointmentMapper) {
        return new AppointmentRepositoryImpl(jpaRepository, appointmentMapper);
    }
}