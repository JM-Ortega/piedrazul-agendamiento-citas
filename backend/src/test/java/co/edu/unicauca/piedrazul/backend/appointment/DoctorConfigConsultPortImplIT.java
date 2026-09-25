package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration.DoctorConfigConsultPortImpl;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integración real de {@link DoctorConfigConsultPortImpl} contra el {@code doctors} real —
 * acotada a {@code getDoctorName}, el único método con lógica propia (unwrap de lista vacía
 * -> null). El resto del puerto queda fuera por costo de wiring (HolidayManager/ScheduleService
 * no cablean nada nuevo, ver plan).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PersonExternalServiceImp.class, DoctorConfigConsultPortImplIT.TestBeans.class})
class DoctorConfigConsultPortImplIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestBeans {
        @Bean
        DoctorExternalService doctorExternalService(DoctorRepository doctorRepository,
                PersonExternalService personExternalService) {
            // getDoctorName/getDoctorInfoByIds no tocan scheduleService ni holidayManager.
            return new DoctorExternalServiceImpl(doctorRepository, null, personExternalService, null);
        }

        @Bean
        DoctorConfigConsultPort doctorConfigConsultPort(DoctorExternalService doctorExternalService) {
            return new DoctorConfigConsultPortImpl(doctorExternalService);
        }
    }

    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private DoctorRepository doctorRepository;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    @Test
    void getDoctorNameShouldReturnFullNameWhenDoctorExists() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Ana", "Ruiz", "3001234567", null, null);
        doctorRepository.save(new Doctor(person.id(), LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));

        String name = doctorConfigConsultPort.getDoctorName(person.id());

        assertThat(name).isEqualTo("Ana Ruiz");
    }

    @Test
    void getDoctorNameShouldReturnNullWhenDoctorDoesNotExist() {
        String name = doctorConfigConsultPort.getDoctorName(UUID.randomUUID());

        assertThat(name).isNull();
    }
}
