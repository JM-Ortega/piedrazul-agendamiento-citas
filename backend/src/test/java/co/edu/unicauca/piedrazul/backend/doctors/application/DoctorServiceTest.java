package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.*;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.SpecialtyRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias para DoctorService con dependencias mockeadas.
 *
 * Supuestos (AJUSTAR si difieren de tu código real):
 * - CreateDoctorRequest es un record con:
 *     laborStart, laborEnd, bookingWindowWeeks, appointmentInterval,
 *     specialty() -> List<SpecialtyCode>, schedules() -> List<ScheduleRequest>
 * - ScheduleRequest es un record con workday(), startTime(), endTime()
 * - PersonSummary es un record con id(), firstName(), lastName()
 * - DoctorDetailedResponse.fromEntity(Doctor, String nombreCompleto) existe como estático
 */
@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;
    @Mock
    private AppointmentExternalService appointmentExternalService;
    @Mock
    private PersonExternalService personExternalService;
    @Mock
    private SpecialtyRepository specialtyRepository;

    private DoctorService doctorService;

    private UUID personId;

    @BeforeEach
    void setUp() {
        doctorService = new DoctorService(doctorRepository, appointmentExternalService,
                personExternalService, specialtyRepository);
        personId = UUID.randomUUID();
    }

    /**
     * Specialty no expone constructor público ni setters (solo @Id code y name, con
     * equals/hashCode basados únicamente en code). Se arma vía reflexión, asumiendo
     * que tiene constructor sin argumentos (típico en una entidad JPA).
     */
    private Specialty buildSpecialty(SpecialtyCode code) {
        Specialty specialty = new Specialty();
        ReflectionTestUtils.setField(specialty, "code", code);
        ReflectionTestUtils.setField(specialty, "name", code.name());
        return specialty;
    }

    @Nested
    class CreateDoctorTests {

        @Test
        void shouldThrowWhenLaborStartIsNull() {
            CreateDoctorRequest request = new CreateDoctorRequest(
                    List.of(SpecialtyCode.MEDICINA_GENERAL), null, LocalDate.now().plusMonths(1), 4, 30,
                    null);

            assertThatThrownBy(() -> doctorService.createDoctor(personId, request))
                    .isInstanceOf(DoctorValidationException.class);

            verifyNoInteractions(doctorRepository, personExternalService);
        }

        @Test
        void shouldThrowWhenLaborEndBeforeLaborStart() {
            CreateDoctorRequest request = new CreateDoctorRequest(
                    List.of(SpecialtyCode.MEDICINA_GENERAL),LocalDate.now(), LocalDate.now().minusDays(1), 4, 30,
                     null);

            assertThatThrownBy(() -> doctorService.createDoctor(personId, request))
                    .isInstanceOf(DateConflictException.class);
        }

        @Test
        void shouldThrowWhenSpecialtyDoesNotExist() {
            CreateDoctorRequest request = new CreateDoctorRequest(
                    List.of(SpecialtyCode.QUIROPRAXIA), LocalDate.now(), LocalDate.now().plusMonths(1), 4, 30,
                     null);

            when(specialtyRepository.findById(SpecialtyCode.QUIROPRAXIA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.createDoctor(personId, request))
                    .isInstanceOf(DoctorInvalidSpecialty.class);

            verify(doctorRepository, never()).save(any());
        }

        @Test
        void shouldCreateInactiveDoctorWhenNoSchedulesProvided() {
            CreateDoctorRequest request = new CreateDoctorRequest(
                    List.of(SpecialtyCode.MEDICINA_GENERAL), LocalDate.now(), LocalDate.now().plusMonths(6), 4, 30,
                     null);

            Specialty specialty = buildSpecialty(SpecialtyCode.MEDICINA_GENERAL);
            when(specialtyRepository.findById(SpecialtyCode.MEDICINA_GENERAL)).thenReturn(Optional.of(specialty));

            doctorService.createDoctor(personId, request);

            ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
            verify(doctorRepository).save(captor.capture());
            Doctor saved = captor.getValue();

            assertThat(saved.isStatus()).isFalse();
            verify(personExternalService).revokeDoctorRole(personId);
            verify(personExternalService, never()).ensureDoctorRole(any());
        }

        @Test
        void shouldActivateDoctorWhenSchedulesMakeItEligible() {
            ScheduleRequest scheduleRequest = new ScheduleRequest(LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.LUNES);
            CreateDoctorRequest request = new CreateDoctorRequest(
                    List.of(SpecialtyCode.MEDICINA_GENERAL), LocalDate.now(), LocalDate.now().plusMonths(6), 4, 30,
                     List.of(new ScheduleRequest(LocalTime.of(5, 0), LocalTime.of(9, 0), Workday.LUNES)));

            Specialty specialty = buildSpecialty(SpecialtyCode.MEDICINA_GENERAL);
            when(specialtyRepository.findById(SpecialtyCode.MEDICINA_GENERAL)).thenReturn(Optional.of(specialty));

            doctorService.createDoctor(personId, request);

            ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
            verify(doctorRepository).save(captor.capture());
            Doctor saved = captor.getValue();

            assertThat(saved.isStatus()).isTrue();
            verify(personExternalService).ensureDoctorRole(personId);
            verify(personExternalService, never()).revokeDoctorRole(any());
        }
    }

    @Nested
    class DeleteDoctorTests {

        @Test
        void shouldThrowWhenPersonIdIsNull() {
            assertThatThrownBy(() -> doctorService.deleteDoctor(null))
                    .isInstanceOf(DoctorValidationException.class);

            verifyNoInteractions(doctorRepository);
        }

        @Test
        void shouldDoNothingWhenDoctorNotFound() {
            when(doctorRepository.findById(personId)).thenReturn(Optional.empty());

            doctorService.deleteDoctor(personId);

            verify(doctorRepository, never()).delete(any());
        }

        @Test
        void shouldDeleteWhenDoctorExists() {
            Doctor doctor = new Doctor(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 4, false, 30);
            when(doctorRepository.findById(personId)).thenReturn(Optional.of(doctor));

            doctorService.deleteDoctor(personId);

            verify(doctorRepository).delete(doctor);
        }
    }

    @Nested
    class UpdateDoctorInfoTests {

        @Test
        void shouldThrowWhenDoctorNotFound() {
            when(doctorRepository.findById(personId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    doctorService.updateDoctorInfo(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 30, 4)
            ).isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        void shouldUpdateAndSaveWhenDoctorExists() {
            Doctor doctor = new Doctor(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 4, false, 30);
            when(doctorRepository.findById(personId)).thenReturn(Optional.of(doctor));
            LocalDate newStart = LocalDate.now();
            LocalDate newEnd = LocalDate.now().plusMonths(3);

            doctorService.updateDoctorInfo(personId, newStart, newEnd, 45, 6);

            assertThat(doctor.getLaborEnd()).isEqualTo(newEnd);
            assertThat(doctor.getAppointmentInterval()).isEqualTo(45);
            verify(doctorRepository).save(doctor);
        }
    }

    @Nested
    class EnableDoctorTests {

        @Test
        void shouldThrowWhenDoctorNotFound() {
            when(doctorRepository.findById(personId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.enableDoctor(personId))
                    .isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        void shouldActivateSaveAndSyncRoleWhenEligible() {
            Doctor doctor = new Doctor(
                    personId,
                    LocalDate.now(),
                    LocalDate.now().plusMonths(3),
                    4,
                    false,
                    30
            );

            doctor.updateSchedule(
                    Workday.LUNES,
                    LocalTime.of(8, 0),
                    LocalTime.of(12, 0)
            );

            doctor.addSpecialty(
                    buildSpecialty(SpecialtyCode.MEDICINA_GENERAL)
            );

            when(doctorRepository.findById(personId))
                    .thenReturn(Optional.of(doctor));

            doctorService.enableDoctor(personId);

            assertThat(doctor.isStatus()).isTrue();
            verify(doctorRepository).save(doctor);
            verify(personExternalService).ensureDoctorRole(personId);
        }

        @Test
        void shouldPropagateExceptionWhenDoctorCannotBeActivated() {
            Doctor doctor = new Doctor(
                    personId,
                    LocalDate.now(),
                    LocalDate.now().plusMonths(3),
                    4,
                    false,
                    30
            );

            // Sin horarios ni especialidades -> no puede activarse
            when(doctorRepository.findById(personId))
                    .thenReturn(Optional.of(doctor));

            assertThatThrownBy(() -> doctorService.enableDoctor(personId))
                    .isInstanceOf(DoctorValidationException.class);

            verify(doctorRepository, never()).save(any());
            verify(personExternalService, never()).ensureDoctorRole(any());
        }
    }

    @Nested
    class DisableDoctorTests {

        @Test
        void shouldThrowWhenDoctorNotFound() {
            when(doctorRepository.findById(personId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.disableDoctor(personId))
                    .isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        void shouldDisableSaveAndSyncRoleWhenNoScheduledAppointments() {
            Doctor doctor = new Doctor(
                    personId,
                    LocalDate.now(),
                    LocalDate.now().plusMonths(3),
                    4,
                    true,
                    30
            );

            when(doctorRepository.findById(personId))
                    .thenReturn(Optional.of(doctor));

            when(appointmentExternalService.hasScheduledAppointments(personId))
                    .thenReturn(false);

            doctorService.disableDoctor(personId);

            assertThat(doctor.isStatus()).isFalse();
            verify(appointmentExternalService)
                    .hasScheduledAppointments(personId);
            verify(doctorRepository).save(doctor);
            verify(personExternalService).revokeDoctorRole(personId);
        }

        @Test
        void shouldThrowWhenDoctorHasScheduledAppointments() {
            Doctor doctor = new Doctor(
                    personId,
                    LocalDate.now(),
                    LocalDate.now().plusMonths(3),
                    4,
                    true,
                    30
            );

            when(doctorRepository.findById(personId))
                    .thenReturn(Optional.of(doctor));

            when(appointmentExternalService.hasScheduledAppointments(personId))
                    .thenReturn(true);

            assertThatThrownBy(() -> doctorService.disableDoctor(personId))
                    .isInstanceOf(DoctorHasScheduledAppointments.class);

            assertThat(doctor.isStatus()).isTrue();

            verify(appointmentExternalService)
                    .hasScheduledAppointments(personId);
            verify(doctorRepository, never()).save(any());
            verify(personExternalService, never()).revokeDoctorRole(any());
        }
    }

    @Nested
    class QueryTests {

        @Test
        void findAllDoctorsShouldDelegateToRepository() {
            List<Doctor> doctors = List.of(new Doctor(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 4, false, 30));
            when(doctorRepository.findAll()).thenReturn(doctors);

            List<Doctor> result = doctorService.findAllDoctors();

            assertThat(result).isEqualTo(doctors);
        }

        @Test
        void findByUserIdShouldThrowWhenDoctorNotFound() {
            UUID keycloakId = UUID.randomUUID();
            when(personExternalService.findPersonIdByUserId(keycloakId)).thenReturn(personId);
            when(doctorRepository.findByPersonId(personId)).thenReturn(null);

            assertThatThrownBy(() -> doctorService.findByUserId(keycloakId))
                    .isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        void findByUserIdShouldReturnDoctorWhenFound() {
            UUID keycloakId = UUID.randomUUID();
            Doctor doctor = new Doctor(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 4, false, 30);
            when(personExternalService.findPersonIdByUserId(keycloakId)).thenReturn(personId);
            when(doctorRepository.findByPersonId(personId)).thenReturn(doctor);

            assertThat(doctorService.findByUserId(keycloakId)).isEqualTo(doctor);
        }

        @Test
        void getDoctorBySpecialityShouldDelegateToRepository() {
            when(doctorRepository.findBySpecialtiesCode(SpecialtyCode.FISIOTERAPIA)).thenReturn(List.of());

            List<Doctor> result = doctorService.getDoctorBySpeciality(SpecialtyCode.FISIOTERAPIA);

            assertThat(result).isEmpty();
            verify(doctorRepository).findBySpecialtiesCode(SpecialtyCode.FISIOTERAPIA);
        }

        @Test
        void getAllSpecialtiesShouldReturnAllEnumValues() {
            List<SpecialtyCode> result = doctorService.getAllSpecialties();

            assertThat(result).containsExactlyInAnyOrder(SpecialtyCode.values());
        }
    }

    @Nested
    class GetSpecialtiesTests {

        @Test
        void shouldThrowWhenNoActiveDoctors() {
            when(doctorRepository.findAllDistinctSpecialtyCodesByActiveDoctors()).thenReturn(List.of());

            assertThatThrownBy(() -> doctorService.getSpecialties(UUID.randomUUID()))
                    .isInstanceOf(NoAvailableDoctorsException.class);
        }

        @Test
        void shouldReturnOnlyMedicinaGeneralForNewPatient() {
            UUID patientId = UUID.randomUUID();
            when(doctorRepository.findAllDistinctSpecialtyCodesByActiveDoctors())
                    .thenReturn(List.of("MEDICINA_GENERAL", "QUIROPRAXIA"));
            when(appointmentExternalService.isNewPatient(patientId)).thenReturn(true);

            List<SpecialtyCode> result = doctorService.getSpecialties(patientId);

            assertThat(result).containsExactly(SpecialtyCode.MEDICINA_GENERAL);
        }

        @Test
        void shouldReturnEmptyForNewPatientWhenNoGeneralMedicineAvailable() {
            UUID patientId = UUID.randomUUID();
            when(doctorRepository.findAllDistinctSpecialtyCodesByActiveDoctors())
                    .thenReturn(List.of("QUIROPRAXIA"));
            when(appointmentExternalService.isNewPatient(patientId)).thenReturn(true);

            List<SpecialtyCode> result = doctorService.getSpecialties(patientId);

            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnAllActiveSpecialtiesForExistingPatient() {
            UUID patientId = UUID.randomUUID();
            when(doctorRepository.findAllDistinctSpecialtyCodesByActiveDoctors())
                    .thenReturn(List.of("MEDICINA_GENERAL", "QUIROPRAXIA"));
            when(appointmentExternalService.isNewPatient(patientId)).thenReturn(false);

            List<SpecialtyCode> result = doctorService.getSpecialties(patientId);

            assertThat(result).containsExactlyInAnyOrder(SpecialtyCode.MEDICINA_GENERAL, SpecialtyCode.QUIROPRAXIA);
        }

        @Test
        void shouldTreatNullPatientIdAsNewPatient() {
            when(doctorRepository.findAllDistinctSpecialtyCodesByActiveDoctors())
                    .thenReturn(List.of("MEDICINA_GENERAL"));

            List<SpecialtyCode> result = doctorService.getSpecialties(null);

            assertThat(result).containsExactly(SpecialtyCode.MEDICINA_GENERAL);
            verifyNoInteractions(appointmentExternalService);
        }
    }

    @Nested
    class ChangeSpecialtiesTests {

        @Test
        void shouldThrowWhenDoctorNotFound() {
            when(doctorRepository.findById(personId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    doctorService.changeSpecialties(personId, List.of(SpecialtyCode.MEDICINA_GENERAL))
            ).isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        void shouldThrowWhenSomeRequestedSpecialtyDoesNotExist() {
            Doctor doctor = new Doctor(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 4, false, 30);
            when(doctorRepository.findById(personId)).thenReturn(Optional.of(doctor));
            when(specialtyRepository.findAllById(anyList()))
                    .thenReturn(List.of(buildSpecialty(SpecialtyCode.TERAPIA_NEURAL))); // solo devuelve 1 de 2 pedidas

            assertThatThrownBy(() ->
                    doctorService.changeSpecialties(personId,
                            List.of(SpecialtyCode.TERAPIA_NEURAL, SpecialtyCode.MEDICINA_GENERAL))
            ).isInstanceOf(IllegalArgumentException.class);

            verify(doctorRepository, never()).save(any());
        }

        @Test
        void shouldReplaceSpecialtiesKeepingOnlyRequestedOnes() {
            Doctor doctor = new Doctor(personId, LocalDate.now(), LocalDate.now().plusMonths(1), 4, false, 30);
            Specialty oldSpecialty = buildSpecialty(SpecialtyCode.MEDICINA_GENERAL);
            doctor.addSpecialty(oldSpecialty);
            when(doctorRepository.findById(personId)).thenReturn(Optional.of(doctor));

            Specialty newSpecialty = buildSpecialty(SpecialtyCode.TERAPIA_NEURAL);
            when(specialtyRepository.findAllById(List.of(SpecialtyCode.TERAPIA_NEURAL)))
                    .thenReturn(List.of(newSpecialty));

            doctorService.changeSpecialties(personId, List.of(SpecialtyCode.TERAPIA_NEURAL));

            assertThat(doctor.getSpecialties()).containsExactly(newSpecialty);
            verify(doctorRepository).save(doctor);
        }
    }
}
