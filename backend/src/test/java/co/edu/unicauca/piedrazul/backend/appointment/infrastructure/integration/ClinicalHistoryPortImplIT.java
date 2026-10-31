package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.application.MedicalCheckupExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.exception.MedicalCheckupAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.infrastructure.persistence.MedicalCheckupRepository;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
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
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real de {@link ClinicalHistoryPortImpl} contra el módulo medicalCheckup.
 * {@code clinical_history} tiene FK reales hacia {@code patient} y {@code appointment}, así
 * que el fixture arma ambos de verdad (mismo patrón que {@code AppointmentRepositoryQueryIT}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ClinicalHistoryPortImpl.class,
        MedicalCheckupExternalServiceImpl.class,
        SecurityContextExtractor.class,
        AppointmentMapper.class,
        PersonExternalServiceImp.class,
        ClinicalHistoryPortImplIT.TestBeans.class
})
class ClinicalHistoryPortImplIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestBeans {
        @Bean
        AppointmentRepository appointmentRepository(AppointmentJpaRepository jpaRepository, AppointmentMapper mapper) {
            return new AppointmentRepositoryImpl(jpaRepository, mapper);
        }
    }

    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private ClinicalHistoryPort clinicalHistoryPort;
    @Autowired
    private MedicalCheckupRepository medicalCheckupRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DoctorRepository doctorRepository;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private UUID newPatient() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Pat", "Ient", "3000000000", null, null);
        patientRepository.save(new Patient(person.id(), Sex.FEMENINO, LocalDate.of(1990, 1, 1), null));
        return person.id();
    }

    private UUID newDoctor() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Doc", "Tor", "3000000001", null, null);
        doctorRepository.save(new Doctor(person.id(), LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));
        return person.id();
    }

    private UUID newAppointment(UUID patientId) {
        Appointment appointment = Appointment.reconstruct(
                null, newDoctor(), patientId, SpecialtyCode.FISIOTERAPIA, AppointmentState.AGENDADA,
                LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)), SchedulingOrigin.MANUAL);
        return appointmentRepository.save(appointment).getIdAppointment();
    }

    @Test
    void registerClinicalHistoryShouldPersistANewRecord() {
        UUID patientId = newPatient();
        UUID appointmentId = newAppointment(patientId);

        clinicalHistoryPort.registerClinicalHistory(
                appointmentId, patientId, "Dra. Prueba", "Paciente estable", LocalDate.now());

        assertThat(medicalCheckupRepository.existsByIdAppointment(appointmentId)).isTrue();
    }

    @Test
    void registerClinicalHistoryShouldThrowWhenAppointmentAlreadyHasOne() {
        UUID patientId = newPatient();
        UUID appointmentId = newAppointment(patientId);
        clinicalHistoryPort.registerClinicalHistory(
                appointmentId, patientId, "Dra. Prueba", "Primera nota", LocalDate.now());

        assertThatThrownBy(() -> clinicalHistoryPort.registerClinicalHistory(
                appointmentId, patientId, "Dra. Prueba", "Segunda nota", LocalDate.now()))
                .isInstanceOf(MedicalCheckupAlreadyExistsException.class);
    }
}
