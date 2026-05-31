package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorValidationException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoctorService - Tests Unitarios")
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private UserModuleApi userModuleApi;

    @Mock
    private AppointmentExternalService appointmentExternalService;

    @InjectMocks
    private DoctorService doctorService;

    private UUID doctorId;
    private UUID userId;
    private Doctor activeDoctorFixture;
    private Doctor inactiveDoctorFixture;

    @BeforeEach
    void setUp() {
        doctorId = UUID.randomUUID();
        userId   = UUID.randomUUID();

        // Doctor cuyo rango laboral cubre la fecha actual → activo
        activeDoctorFixture = buildDoctor(doctorId, userId,
                LocalDate.now().minusDays(10),
                LocalDate.now().plusDays(30),
                true);

        // Doctor cuyo rango laboral ya venció → inactivo
        inactiveDoctorFixture = buildDoctor(doctorId, userId,
                LocalDate.now().minusDays(60),
                LocalDate.now().minusDays(1),
                false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────────────────

    private Doctor buildDoctor(UUID id, UUID uid, LocalDate start, LocalDate end, boolean status) {
        Doctor d = new Doctor();
        d.setIdDoctor(id);
        d.setIdUser(uid);
        d.setFirstName("Juan");
        d.setLastName("Pérez");
        d.setLaborStart(start);
        d.setLaborEnd(end);
        d.setStatus(status);
        d.setSpecialty(new ArrayList<>(List.of(Specialty.MEDICINA_GENERAL)));
        d.setSchedules(new ArrayList<>());
        d.setAppointmentInterval(30);
        return d;
    }

    private Schedule buildSchedule(Doctor doctor, LocalTime start, LocalTime end) {
        return new Schedule(doctor, start, end, null);
    }

    private CreateDoctorRequest buildCreateRequest(LocalDate start, LocalDate end) {
        return new CreateDoctorRequest(
                DocumentType.CEDULA,
                "3001234567",
                List.of(Specialty.MEDICINA_GENERAL),
                start,
                end,
                30,
                List.of()
        );
    }

    // =========================================================================
    // createDoctor
    // =========================================================================

    @Nested
    @DisplayName("createDoctor()")
    class CreateDoctorTests {

        @Test
        @DisplayName("Crea doctor activo cuando la fecha actual está dentro del rango laboral")
        void createDoctor_activeDateRange_savesActiveDoctor() {
            CreateDoctorRequest request = buildCreateRequest(
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusDays(10));

            Doctor saved = buildDoctor(UUID.randomUUID(), userId,
                    request.laborStart(), request.laborEnd(), true);
            when(doctorRepository.save(any(Doctor.class))).thenReturn(saved);

            doctorService.createDoctor(userId, "Ana", "López", "123456", request);

            ArgumentCaptor<Doctor> captor = ArgumentCaptor.forClass(Doctor.class);
            verify(doctorRepository).save(captor.capture());
            assertThat(captor.getValue().isStatus()).isTrue();
            verifyNoInteractions(userModuleApi); // activo → no se desactiva
        }

        @Test
        @DisplayName("Desactiva usuario cuando el doctor creado queda inactivo")
        void createDoctor_inactiveDateRange_deactivatesUser() {
            CreateDoctorRequest request = buildCreateRequest(
                    LocalDate.now().plusDays(5),  // aún no empieza
                    LocalDate.now().plusDays(20));

            Doctor saved = buildDoctor(UUID.randomUUID(), userId,
                    request.laborStart(), request.laborEnd(), false);
            when(doctorRepository.save(any(Doctor.class))).thenReturn(saved);

            doctorService.createDoctor(userId, "Carlos", "Ruiz", "654321", request);

            verify(userModuleApi).deactivateUser(saved.getIdUser());
        }

        @Test
        @DisplayName("Lanza DoctorValidationException cuando laborStart es null")
        void createDoctor_nullLaborStart_throwsValidationException() {
            CreateDoctorRequest request = buildCreateRequest(null, LocalDate.now().plusDays(5));

            assertThatThrownBy(() ->
                    doctorService.createDoctor(userId, "X", "Y", "111", request))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        @DisplayName("Lanza DoctorValidationException cuando laborEnd es null")
        void createDoctor_nullLaborEnd_throwsValidationException() {
            CreateDoctorRequest request = buildCreateRequest(LocalDate.now(), null);

            assertThatThrownBy(() ->
                    doctorService.createDoctor(userId, "X", "Y", "111", request))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        @DisplayName("Lanza DateConflictException cuando laborEnd es anterior a laborStart")
        void createDoctor_endBeforeStart_throwsDateConflictException() {
            CreateDoctorRequest request = buildCreateRequest(
                    LocalDate.now().plusDays(5),
                    LocalDate.now().minusDays(1));

            assertThatThrownBy(() ->
                    doctorService.createDoctor(userId, "X", "Y", "111", request))
                    .isInstanceOf(DateConflictException.class);
        }
    }

    // =========================================================================
    // updateDoctorStatus
    // =========================================================================

    @Nested
    @DisplayName("updateDoctorStatus()")
    class UpdateDoctorStatusTests {

        @Test
        @DisplayName("Activa usuario cuando el doctor pasa a estar activo")
        void updateDoctorStatus_doctorBecomesActive_activatesUser() {
            // El doctor está guardado como inactivo pero su rango laboral cubre hoy
            Doctor doctor = buildDoctor(doctorId, userId,
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusDays(10),
                    false); // estado desactualizado
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
            when(doctorRepository.save(any())).thenReturn(doctor);

            doctorService.updateDoctorStatus(doctorId);

            verify(doctorRepository).save(any());
            verify(userModuleApi).activateUser(userId);
        }

        @Test
        @DisplayName("Desactiva usuario cuando el doctor pasa a estar inactivo")
        void updateDoctorStatus_doctorBecomesInactive_deactivatesUser() {
            Doctor doctor = buildDoctor(doctorId, userId,
                    LocalDate.now().minusDays(30),
                    LocalDate.now().minusDays(1),
                    true); // estado desactualizado
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
            when(doctorRepository.save(any())).thenReturn(doctor);

            doctorService.updateDoctorStatus(doctorId);

            verify(userModuleApi).deactivateUser(userId);
        }

        @Test
        @DisplayName("No persiste si el estado no cambió")
        void updateDoctorStatus_statusUnchanged_doesNotSave() {
            // Doctor activo cuyo rango laboral sigue activo hoy
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            doctorService.updateDoctorStatus(doctorId);

            verify(doctorRepository, never()).save(any());
        }

        @Test
        @DisplayName("Lanza DoctorNotFoundException cuando el doctor no existe")
        void updateDoctorStatus_doctorNotFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.updateDoctorStatus(doctorId))
                    .isInstanceOf(DoctorNotFoundException.class);
        }
    }

    // =========================================================================
    // updateDoctorLaborStart
    // =========================================================================

    @Nested
    @DisplayName("updateDoctorLaborStart()")
    class UpdateDoctorLaborStartTests {

        @Test
        @DisplayName("Actualiza fecha de inicio correctamente")
        void updateLaborStart_validDate_savesDoctor() {
            LocalDate newStart = LocalDate.now().minusDays(5);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));
            when(doctorRepository.save(any())).thenReturn(activeDoctorFixture);

            doctorService.updateDoctorLaborStart(doctorId, newStart);

            assertThat(activeDoctorFixture.getLaborStart()).isEqualTo(newStart);
            verify(doctorRepository).save(activeDoctorFixture);
        }

        @Test
        @DisplayName("Lanza DoctorValidationException cuando newLaborStart es null")
        void updateLaborStart_null_throwsValidationException() {
            assertThatThrownBy(() -> doctorService.updateDoctorLaborStart(doctorId, null))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        @DisplayName("Lanza DateConflictException cuando newStart es posterior a laborEnd")
        void updateLaborStart_afterLaborEnd_throwsDateConflictException() {
            LocalDate newStart = activeDoctorFixture.getLaborEnd().plusDays(1);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            assertThatThrownBy(() -> doctorService.updateDoctorLaborStart(doctorId, newStart))
                    .isInstanceOf(DateConflictException.class);
        }

        @Test
        @DisplayName("Lanza DoctorNotFoundException cuando el doctor no existe")
        void updateLaborStart_doctorNotFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.updateDoctorLaborStart(doctorId, LocalDate.now()))
                    .isInstanceOf(DoctorNotFoundException.class);
        }
    }

    // =========================================================================
    // updateDoctorLaborEnd
    // =========================================================================

    @Nested
    @DisplayName("updateDoctorLaborEnd()")
    class UpdateDoctorLaborEndTests {

        @Test
        @DisplayName("Actualiza fecha de fin correctamente")
        void updateLaborEnd_validDate_savesDoctor() {
            LocalDate newEnd = LocalDate.now().plusDays(60);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));
            when(doctorRepository.save(any())).thenReturn(activeDoctorFixture);

            doctorService.updateDoctorLaborEnd(doctorId, newEnd);

            assertThat(activeDoctorFixture.getLaborEnd()).isEqualTo(newEnd);
            verify(doctorRepository).save(activeDoctorFixture);
        }

        @Test
        @DisplayName("Lanza DoctorValidationException cuando newLaborEnd es null")
        void updateLaborEnd_null_throwsValidationException() {
            assertThatThrownBy(() -> doctorService.updateDoctorLaborEnd(doctorId, null))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        @DisplayName("Lanza DateConflictException cuando newEnd es anterior a laborStart")
        void updateLaborEnd_beforeLaborStart_throwsDateConflictException() {
            LocalDate newEnd = activeDoctorFixture.getLaborStart().minusDays(1);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            assertThatThrownBy(() -> doctorService.updateDoctorLaborEnd(doctorId, newEnd))
                    .isInstanceOf(DateConflictException.class);
        }

        @Test
        @DisplayName("Lanza DateConflictException cuando el doctor no tiene laborStart registrado")
        void updateLaborEnd_noLaborStart_throwsDateConflictException() {
            activeDoctorFixture.setLaborStart(null);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            assertThatThrownBy(() -> doctorService.updateDoctorLaborEnd(doctorId, LocalDate.now().plusDays(10)))
                    .isInstanceOf(DateConflictException.class);
        }
    }

    // =========================================================================
    // updateDoctorAppointmentInterval
    // =========================================================================

    @Nested
    @DisplayName("updateDoctorAppointmentInterval()")
    class UpdateAppointmentIntervalTests {

        @Test
        @DisplayName("Actualiza intervalo cuando encaja en al menos un horario")
        void updateInterval_fitsOneSchedule_savesDoctor() {
            Schedule schedule = buildSchedule(activeDoctorFixture,
                    LocalTime.of(8, 0), LocalTime.of(12, 0)); // 240 min
            activeDoctorFixture.setSchedules(List.of(schedule));
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));
            when(doctorRepository.save(any())).thenReturn(activeDoctorFixture);

            doctorService.updateDoctorAppointmentInterval(doctorId, 60);

            assertThat(activeDoctorFixture.getAppointmentInterval()).isEqualTo(60);
            verify(doctorRepository).save(activeDoctorFixture);
        }

        @Test
        @DisplayName("Lanza IllegalArgumentException cuando el intervalo supera todos los horarios")
        void updateInterval_largerThanAllSchedules_throwsIllegalArgument() {
            Schedule schedule = buildSchedule(activeDoctorFixture,
                    LocalTime.of(8, 0), LocalTime.of(8, 20)); // solo 20 min
            activeDoctorFixture.setSchedules(List.of(schedule));
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            assertThatThrownBy(() -> doctorService.updateDoctorAppointmentInterval(doctorId, 30))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Lanza DoctorValidationException cuando el intervalo es 0 o negativo")
        void updateInterval_nonPositive_throwsValidationException() {
            assertThatThrownBy(() -> doctorService.updateDoctorAppointmentInterval(doctorId, 0))
                    .isInstanceOf(DoctorValidationException.class);
            assertThatThrownBy(() -> doctorService.updateDoctorAppointmentInterval(doctorId, -5))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        @DisplayName("Permite cualquier intervalo cuando no hay horarios registrados")
        void updateInterval_noSchedules_savesDoctor() {
            activeDoctorFixture.setSchedules(Collections.emptyList());
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));
            when(doctorRepository.save(any())).thenReturn(activeDoctorFixture);

            doctorService.updateDoctorAppointmentInterval(doctorId, 120);

            assertThat(activeDoctorFixture.getAppointmentInterval()).isEqualTo(120);
        }
    }

    // =========================================================================
    // enableDoctor
    // =========================================================================

    @Nested
    @DisplayName("enableDoctor()")
    class EnableDoctorTests {

        @Test
        @DisplayName("Habilita doctor y activa usuario correctamente")
        void enableDoctor_validDates_enablesAndActivatesUser() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(inactiveDoctorFixture));
            when(doctorRepository.save(any())).thenReturn(inactiveDoctorFixture);

            LocalDate newStart = LocalDate.now();
            LocalDate newEnd   = LocalDate.now().plusDays(30);
            doctorService.enableDoctor(doctorId, newStart, newEnd);

            assertThat(inactiveDoctorFixture.isStatus()).isTrue();
            assertThat(inactiveDoctorFixture.getLaborStart()).isEqualTo(newStart);
            assertThat(inactiveDoctorFixture.getLaborEnd()).isEqualTo(newEnd);
            verify(userModuleApi).activateUser(userId);
        }

        @Test
        @DisplayName("Lanza DateConflictException cuando end es anterior a start")
        void enableDoctor_endBeforeStart_throwsDateConflictException() {
            assertThatThrownBy(() -> doctorService.enableDoctor(doctorId,
                    LocalDate.now().plusDays(5),
                    LocalDate.now().minusDays(1)))
                    .isInstanceOf(DateConflictException.class);
        }

        @Test
        @DisplayName("Lanza DoctorNotFoundException cuando el doctor no existe")
        void enableDoctor_doctorNotFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.enableDoctor(doctorId,
                    LocalDate.now(), LocalDate.now().plusDays(10)))
                    .isInstanceOf(DoctorNotFoundException.class);
        }
    }

    // =========================================================================
    // disableDoctor
    // =========================================================================

    @Nested
    @DisplayName("disableDoctor()")
    class DisableDoctorTests {

        @Test
        @DisplayName("Deshabilita doctor activo y desactiva usuario")
        void disableDoctor_activeDoctor_disablesAndDeactivatesUser() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));
            when(doctorRepository.save(any())).thenReturn(activeDoctorFixture);

            doctorService.disableDoctor(doctorId, false);

            assertThat(activeDoctorFixture.isStatus()).isFalse();
            assertThat(activeDoctorFixture.getLaborEnd()).isEqualTo(LocalDate.now());
            verify(userModuleApi).deactivateUser(userId);
        }

        @Test
        @DisplayName("Lanza DateConflictException cuando el doctor aún no ha iniciado labores y force=false")
        void disableDoctor_notStartedAndNoForce_throwsDateConflictException() {
            Doctor futureDoctor = buildDoctor(doctorId, userId,
                    LocalDate.now().plusDays(5),
                    LocalDate.now().plusDays(30),
                    false);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(futureDoctor));

            assertThatThrownBy(() -> doctorService.disableDoctor(doctorId, false))
                    .isInstanceOf(DateConflictException.class)
                    .hasMessageContaining("Inicia:");
        }

        @Test
        @DisplayName("Fuerza deshabilitación con force=true aunque el doctor no haya iniciado")
        void disableDoctor_notStartedWithForce_disablesDoctor() {
            Doctor futureDoctor = buildDoctor(doctorId, userId,
                    LocalDate.now().plusDays(5),
                    LocalDate.now().plusDays(30),
                    false);
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(futureDoctor));
            when(doctorRepository.save(any())).thenReturn(futureDoctor);

            doctorService.disableDoctor(doctorId, true);

            assertThat(futureDoctor.isStatus()).isFalse();
            // Cuando hoy < laborStart, laborEnd debe igualarse a laborStart
            assertThat(futureDoctor.getLaborEnd()).isEqualTo(futureDoctor.getLaborStart());
        }

        @Test
        @DisplayName("Lanza DoctorNotFoundException cuando el doctor no existe")
        void disableDoctor_doctorNotFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.disableDoctor(doctorId, false))
                    .isInstanceOf(DoctorNotFoundException.class);
        }
    }

    // =========================================================================
    // addSpecialities / removeSpecialities
    // =========================================================================

    @Nested
    @DisplayName("addSpecialities() y removeSpecialities()")
    class SpecialtyTests {

        @Test
        @DisplayName("Agrega especialidades al doctor")
        void addSpecialities_validDoctor_addsSpecialties() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            doctorService.addSpecialities(doctorId, List.of(Specialty.FISIOTERAPIA));

            assertThat(activeDoctorFixture.getSpecialty()).contains(Specialty.FISIOTERAPIA);
        }

        @Test
        @DisplayName("Elimina especialidades del doctor")
        void removeSpecialities_validDoctor_removesSpecialties() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            doctorService.removeSpecialities(doctorId, List.of(Specialty.MEDICINA_GENERAL));

            assertThat(activeDoctorFixture.getSpecialty()).doesNotContain(Specialty.MEDICINA_GENERAL);
        }

        @Test
        @DisplayName("Lanza DoctorNotFoundException al agregar especialidades a doctor inexistente")
        void addSpecialities_doctorNotFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.addSpecialities(doctorId, List.of(Specialty.FISIOTERAPIA)))
                    .isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        @DisplayName("Lanza DoctorNotFoundException al eliminar especialidades de doctor inexistente")
        void removeSpecialities_doctorNotFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.removeSpecialities(doctorId, List.of(Specialty.MEDICINA_GENERAL)))
                    .isInstanceOf(DoctorNotFoundException.class);
        }
    }

    // =========================================================================
    // getSpecialties
    // =========================================================================

    @Nested
    @DisplayName("getSpecialties()")
    class GetSpecialtiesTests {

        @Test
        @DisplayName("Retorna solo MEDICINA_GENERAL para paciente nuevo si está disponible")
        void getSpecialties_newPatientWithGeneralMedicine_returnsOnlyGeneralMedicine() {
            UUID patientId = UUID.randomUUID();
            when(appointmentExternalService.isNewPatient(patientId)).thenReturn(true);
            when(doctorRepository.findAllDistinctSpecialtiesByActiveDoctors())
                    .thenReturn(new ArrayList<>(List.of(Specialty.MEDICINA_GENERAL, Specialty.FISIOTERAPIA)));

            List<Specialty> result = doctorService.getSpecialties(patientId);

            assertThat(result).containsExactly(Specialty.MEDICINA_GENERAL);
        }

        @Test
        @DisplayName("Retorna lista vacía para paciente nuevo si MEDICINA_GENERAL no está disponible")
        void getSpecialties_newPatientWithoutGeneralMedicine_returnsEmpty() {
            UUID patientId = UUID.randomUUID();
            when(appointmentExternalService.isNewPatient(patientId)).thenReturn(true);
            when(doctorRepository.findAllDistinctSpecialtiesByActiveDoctors())
                    .thenReturn(List.of(Specialty.FISIOTERAPIA));

            List<Specialty> result = doctorService.getSpecialties(patientId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Retorna todas las especialidades activas para paciente no nuevo")
        void getSpecialties_existingPatient_returnsAllActiveSpecialties() {
            UUID patientId = UUID.randomUUID();
            List<Specialty> all = List.of(Specialty.MEDICINA_GENERAL, Specialty.FISIOTERAPIA);
            when(appointmentExternalService.isNewPatient(patientId)).thenReturn(false);
            when(doctorRepository.findAllDistinctSpecialtiesByActiveDoctors()).thenReturn(all);

            List<Specialty> result = doctorService.getSpecialties(patientId);

            assertThat(result).containsExactlyInAnyOrderElementsOf(all);
        }
    }

    // =========================================================================
    // findAllDoctors / getDoctorById / findByUserId / getDoctorBySpeciality
    // =========================================================================

    @Nested
    @DisplayName("Consultas de doctores")
    class QueryTests {

        @Test
        @DisplayName("findAllDoctors retorna lista del repositorio")
        void findAllDoctors_returnsRepositoryResult() {
            List<Doctor> expected = List.of(activeDoctorFixture);
            when(doctorRepository.findAll()).thenReturn(expected);

            assertThat(doctorService.findAllDoctors()).isEqualTo(expected);
        }

        @Test
        @DisplayName("getDoctorById retorna el doctor cuando existe")
        void getDoctorById_found_returnsDoctor() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(activeDoctorFixture));

            assertThat(doctorService.getDoctorById(doctorId)).isEqualTo(activeDoctorFixture);
        }

        @Test
        @DisplayName("getDoctorById lanza DoctorNotFoundException cuando no existe")
        void getDoctorById_notFound_throwsNotFoundException() {
            when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> doctorService.getDoctorById(doctorId))
                    .isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        @DisplayName("findByUserId retorna el doctor asociado al usuario")
        void findByUserId_found_returnsDoctor() {
            when(doctorRepository.findByIdUser(userId)).thenReturn(activeDoctorFixture);

            assertThat(doctorService.findByUserId(userId)).isEqualTo(activeDoctorFixture);
        }

        @Test
        @DisplayName("findByUserId lanza DoctorNotFoundException cuando no existe")
        void findByUserId_notFound_throwsNotFoundException() {
            when(doctorRepository.findByIdUser(userId)).thenReturn(null);

            assertThatThrownBy(() -> doctorService.findByUserId(userId))
                    .isInstanceOf(DoctorNotFoundException.class);
        }

        @Test
        @DisplayName("getDoctorBySpeciality filtra correctamente por especialidad")
        void getDoctorBySpeciality_returnsFilteredList() {
            when(doctorRepository.findBySpecialtyContaining(Specialty.FISIOTERAPIA))
                    .thenReturn(List.of(activeDoctorFixture));

            List<Doctor> result = doctorService.getDoctorBySpeciality(Specialty.FISIOTERAPIA);

            assertThat(result).containsExactly(activeDoctorFixture);
        }

        @Test
        @DisplayName("getAllSpecialties retorna todos los valores del enum Specialty")
        void getAllSpecialties_returnsAllEnumValues() {
            assertThat(doctorService.getAllSpecialties())
                    .containsExactlyInAnyOrder(Specialty.values());
        }
    }
}