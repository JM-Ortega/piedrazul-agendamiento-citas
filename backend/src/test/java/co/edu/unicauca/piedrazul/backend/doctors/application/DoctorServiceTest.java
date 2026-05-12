package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.*;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private UserModuleApi userModuleApi;

    private DoctorService doctorService;

    @BeforeEach
    void setUp() {
        doctorService = new DoctorService(doctorRepository, userModuleApi);
    }

    @Test
    void createDoctorShouldCreateAndDeactivateUserWhenDoctorStartsInFuture() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate endDate = tomorrow.plusMonths(1);
        UUID userId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();

        CreateDoctorRequest request = new CreateDoctorRequest(
                "Laura",
                "Perez",
                DocumentType.CEDULA,
                "123456",
                "3001234567",
                List.of(Specialty.QUIROPRAXIA),
                tomorrow,
                endDate,
                30,
                List.of(new CreateScheduleRequest(
                        LocalTime.of(8, 0),
                        LocalTime.of(12, 0),
                        Workday.LUNES
                )),
                "laura@test.com",
                "Pass123!"
        );

        when(userModuleApi.getOrCreateDoctorUser(
                "123456",
                "Laura",
                "Perez",
                "laura@test.com",
                "Pass123!"
        )).thenReturn(userId);

        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> {
            Doctor doctor = invocation.getArgument(0);
            doctor.setIdDoctor(doctorId);
            return doctor;
        });

        DoctorResponse response = doctorService.createDoctor(request);

        assertThat(response.id()).isEqualTo(doctorId);
        assertThat(response.name()).isEqualTo("Laura Perez");
        assertThat(response.workdays()).containsExactly(1);

        verify(userModuleApi).getOrCreateDoctorUser(
                "123456",
                "Laura",
                "Perez",
                "laura@test.com",
                "Pass123!"
        );
        verify(userModuleApi).deactivateUser(userId);
        verify(userModuleApi, never()).activateUser(any(UUID.class));
        verify(doctorRepository).save(any(Doctor.class));
    }

    @Test
    void createDoctorShouldCreateAndKeepUserActiveWhenDoctorStartsToday() {
        LocalDate today = LocalDate.now();
        LocalDate endDate = today.plusMonths(1);
        UUID userId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();

        CreateDoctorRequest request = new CreateDoctorRequest(
                "Laura",
                "Perez",
                DocumentType.CEDULA,
                "123456",
                "3001234567",
                List.of(Specialty.FISIOTERAPIA),
                today,
                endDate,
                30,
                List.of(new CreateScheduleRequest(
                        LocalTime.of(8, 0),
                        LocalTime.of(12, 0),
                        Workday.LUNES
                )),
                "laura@test.com",
                "Pass123!"
        );

        when(userModuleApi.getOrCreateDoctorUser(
                "123456",
                "Laura",
                "Perez",
                "laura@test.com",
                "Pass123!"
        )).thenReturn(userId);

        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> {
            Doctor doctor = invocation.getArgument(0);
            doctor.setIdDoctor(doctorId);
            return doctor;
        });

        DoctorResponse response = doctorService.createDoctor(request);

        assertThat(response.id()).isEqualTo(doctorId);

        verify(userModuleApi).getOrCreateDoctorUser(
                "123456",
                "Laura",
                "Perez",
                "laura@test.com",
                "Pass123!"
        );
        verify(userModuleApi, never()).deactivateUser(any(UUID.class));
        verify(userModuleApi, never()).activateUser(any(UUID.class));
        verify(doctorRepository).save(any(Doctor.class));
    }

    @Test
    void createDoctorShouldThrowWhenLaborEndIsNull() {
        CreateDoctorRequest request = new CreateDoctorRequest(
                "Laura",
                "Perez",
                DocumentType.CEDULA,
                "123456",
                "3001234567",
                List.of(Specialty.FISIOTERAPIA),
                LocalDate.now(),
                null,
                30,
                List.of(),
                "laura@test.com",
                "Pass123!"
        );

        assertThatThrownBy(() -> doctorService.createDoctor(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fecha de finalización es obligatoria");

        verify(doctorRepository, never()).save(any(Doctor.class));
        verify(userModuleApi, never()).getOrCreateDoctorUser(any(), any(), any(), any(), any());
    }

    @Test
    void createDoctorShouldThrowWhenLaborEndIsBeforeLaborStart() {
        CreateDoctorRequest request = new CreateDoctorRequest(
                "Laura",
                "Perez",
                DocumentType.CEDULA,
                "123456",
                "3001234567",
                List.of(Specialty.FISIOTERAPIA),
                LocalDate.now(),
                LocalDate.now().minusDays(1),
                30,
                List.of(),
                "laura@test.com",
                "Pass123!"
        );

        assertThatThrownBy(() -> doctorService.createDoctor(request))
                .isInstanceOf(DateConflictException.class)
                .hasMessageContaining("no puede ser anterior a la fecha de inicio");

        verify(doctorRepository, never()).save(any(Doctor.class));
        verify(userModuleApi, never()).getOrCreateDoctorUser(any(), any(), any(), any(), any());
    }

    @Test
    void updateDoctorLaborEndShouldThrowWhenEndDateIsBeforeStartDate() {
        UUID doctorId = UUID.randomUUID();
        Doctor doctor = createDoctorFixture();
        doctor.setLaborStart(LocalDate.now());

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        assertThatThrownBy(() ->
                doctorService.updateDoctorLaborEnd(doctorId, LocalDate.now().minusDays(1)))
                .isInstanceOf(DateConflictException.class)
                .hasMessageContaining("no puede ser anterior a la fecha de inicio");

        verify(doctorRepository, never()).save(any(Doctor.class));
    }

    @Test
    void updateDoctorStatusShouldPersistAndActivateUserWhenStatusChangesToActive() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setStatus(false);
        doctor.setLaborStart(LocalDate.now().minusDays(1));
        doctor.setLaborEnd(LocalDate.now().plusDays(1));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        doctorService.updateDoctorStatus(doctorId);

        verify(doctorRepository).save(doctor);
        verify(userModuleApi).activateUser(userId);
        verify(userModuleApi, never()).deactivateUser(userId);
    }

    @Test
    void updateDoctorStatusShouldNotPersistWhenStatusDoesNotChangeButShouldSyncUserStatus() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setStatus(true);
        doctor.setLaborStart(LocalDate.now().minusDays(1));
        doctor.setLaborEnd(LocalDate.now().plusDays(1));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        doctorService.updateDoctorStatus(doctorId);

        verify(doctorRepository, never()).save(any(Doctor.class));
        verify(userModuleApi).activateUser(userId);
        verify(userModuleApi, never()).deactivateUser(userId);
    }

    @Test
    void disableDoctorShouldThrowWhenDoctorHasNotStartedAndForceIsFalse() {
        UUID doctorId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setLaborStart(LocalDate.now().plusDays(5));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        assertThatThrownBy(() -> doctorService.disableDoctor(doctorId, false))
                .isInstanceOf(DateConflictException.class)
                .hasMessageContaining("aún no ha iniciado labores");

        verify(doctorRepository, never()).save(any(Doctor.class));
        verify(userModuleApi, never()).deactivateUser(any(UUID.class));
    }

    @Test
    void disableDoctorShouldSetLaborEndToStartDateWhenForcedBeforeStart() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDate futureStart = LocalDate.now().plusDays(10);

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setLaborStart(futureStart);
        doctor.setStatus(true);

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        doctorService.disableDoctor(doctorId, true);

        ArgumentCaptor<Doctor> doctorCaptor = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(doctorCaptor.capture());

        Doctor savedDoctor = doctorCaptor.getValue();
        assertThat(savedDoctor.getLaborEnd()).isEqualTo(futureStart);
        assertThat(savedDoctor.isStatus()).isFalse();
        verify(userModuleApi).deactivateUser(userId);
    }

    @Test
    void disableDoctorShouldSetLaborEndToTodayWhenDoctorAlreadyStarted() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setLaborStart(LocalDate.now().minusDays(10));
        doctor.setStatus(true);

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        doctorService.disableDoctor(doctorId, false);

        ArgumentCaptor<Doctor> doctorCaptor = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(doctorCaptor.capture());

        Doctor savedDoctor = doctorCaptor.getValue();
        assertThat(savedDoctor.getLaborEnd()).isEqualTo(LocalDate.now());
        assertThat(savedDoctor.isStatus()).isFalse();
        verify(userModuleApi).deactivateUser(userId);
    }

    @Test
    void updateDoctorAppointmentIntervalShouldThrowWhenIntervalFitsNoSchedule() {
        UUID doctorId = UUID.randomUUID();
        Doctor doctor = createDoctorFixture();
        doctor.setSchedules(List.of(
                new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(8, 30), Workday.LUNES),
                new Schedule(doctor, LocalTime.of(9, 0), LocalTime.of(9, 20), Workday.MARTES)
        ));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        assertThatThrownBy(() -> doctorService.updateDoctorAppointmentInterval(doctorId, 45))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("es mayor a la duración de todos los horarios");

        verify(doctorRepository, never()).save(any(Doctor.class));
    }

    @Test
    void updateDoctorAppointmentIntervalShouldUpdateWhenIntervalFitsAtLeastOneSchedule() {
        UUID doctorId = UUID.randomUUID();
        Doctor doctor = createDoctorFixture();
        doctor.setSchedules(List.of(
                new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(9, 0), Workday.LUNES),
                new Schedule(doctor, LocalTime.of(10, 0), LocalTime.of(10, 20), Workday.MARTES)
        ));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        doctorService.updateDoctorAppointmentInterval(doctorId, 45);

        assertThat(doctor.getAppointmentInterval()).isEqualTo(45);
        verify(doctorRepository).save(doctor);
    }

    @Test
    void updateDoctorLaborStartShouldThrowWhenDoctorNotFound() {
        UUID doctorId = UUID.randomUUID();

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.updateDoctorLaborStart(doctorId, LocalDate.now()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Doctor no encontrado");
    }

    @Test
    void updateDoctorLaborStartShouldDeactivateUserWhenDoctorBecomesInactive() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setLaborEnd(LocalDate.now().plusDays(10));
        doctor.setStatus(true);

        LocalDate futureStart = LocalDate.now().plusDays(5);

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        doctorService.updateDoctorLaborStart(doctorId, futureStart);

        assertThat(doctor.getLaborStart()).isEqualTo(futureStart);
        assertThat(doctor.isStatus()).isFalse();
        verify(userModuleApi).deactivateUser(userId);
        verify(doctorRepository).save(doctor);
    }

    @Test
    void updateDoctorLaborEndShouldActivateUserWhenDoctorBecomesActive() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setLaborStart(LocalDate.now().minusDays(5));
        doctor.setLaborEnd(LocalDate.now().minusDays(1));
        doctor.setStatus(false);

        LocalDate newEnd = LocalDate.now().plusDays(10);

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        doctorService.updateDoctorLaborEnd(doctorId, newEnd);

        assertThat(doctor.getLaborEnd()).isEqualTo(newEnd);
        assertThat(doctor.isStatus()).isTrue();
        verify(userModuleApi).activateUser(userId);
        verify(doctorRepository).save(doctor);
    }

    @Test
    void enableDoctorShouldUpdateDatesActivateUserAndPersistDoctor() {
        UUID doctorId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Doctor doctor = createDoctorFixture();
        doctor.setIdDoctor(doctorId);
        doctor.setIdUser(userId);
        doctor.setStatus(false);

        LocalDate newStart = LocalDate.now().minusDays(1);
        LocalDate newEnd = LocalDate.now().plusMonths(1);

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        doctorService.enableDoctor(doctorId, newStart, newEnd);

        assertThat(doctor.getLaborStart()).isEqualTo(newStart);
        assertThat(doctor.getLaborEnd()).isEqualTo(newEnd);
        assertThat(doctor.isStatus()).isTrue();

        verify(userModuleApi).activateUser(userId);
        verify(doctorRepository).save(doctor);
    }

    private Doctor createDoctorFixture() {
        Doctor doctor = new Doctor();
        doctor.setFirstName("Ana");
        doctor.setLastName("Lopez");
        doctor.setDocumentType(DocumentType.CEDULA);
        doctor.setIdentification("987654");
        doctor.setPhone("3007654321");
        doctor.setSpecialty(List.of(Specialty.FISIOTERAPIA));
        doctor.setStatus(true);
        doctor.setLaborStart(LocalDate.now().minusDays(30));
        doctor.setLaborEnd(LocalDate.now().plusDays(30));
        doctor.setAppointmentInterval(30);
        doctor.setSchedules(List.of());
        return doctor;
    }
}
