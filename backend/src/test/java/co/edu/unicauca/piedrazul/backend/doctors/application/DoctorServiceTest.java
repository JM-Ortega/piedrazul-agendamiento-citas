package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorInvalidSpecialty;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.SpecialtyRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @InjectMocks
    private DoctorService doctorService;

    @Test
    void createDoctorShouldPersistInactiveDoctorAndActivateUserWhenItIsCurrentlyActive() {
        UUID personId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        LocalDate laborStart = LocalDate.now().minusDays(1);
        LocalDate laborEnd = LocalDate.now().plusDays(1);

        Specialty specialty = new Specialty();
        specialty.setCode(SpecialtyCode.MEDICINA_GENERAL);
        specialty.setName("Medicina general");

        when(specialtyRepository.findById(SpecialtyCode.MEDICINA_GENERAL)).thenReturn(Optional.of(specialty));
        when(doctorRepository.save(org.mockito.ArgumentMatchers.any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateDoctorRequest request = new CreateDoctorRequest(
                List.of(SpecialtyCode.MEDICINA_GENERAL),
                laborStart,
                laborEnd,
                20,
                4,
                List.of(new CreateScheduleRequest(LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.LUNES))
        );

        doctorService.createDoctor(personId, request);

        ArgumentCaptor<Doctor> doctorCaptor = ArgumentCaptor.forClass(Doctor.class);
        verify(personExternalService).deactivateUser(personId);
        verify(personExternalService).activateUser(personId);
        verify(doctorRepository).save(doctorCaptor.capture());

        Doctor savedDoctor = doctorCaptor.getValue();
        assertThat(savedDoctor.getPersonId()).isEqualTo(personId);
        assertThat(savedDoctor.getLaborStart()).isEqualTo(laborStart);
        assertThat(savedDoctor.getLaborEnd()).isEqualTo(laborEnd);
        assertThat(savedDoctor.getAppointmentInterval()).isEqualTo(20);
        assertThat(savedDoctor.getBookingWindowWeeks()).isEqualTo(4);
        assertThat(savedDoctor.isStatus()).isTrue();
        assertThat(savedDoctor.getSpecialties()).containsExactly(specialty);
        assertThat(savedDoctor.getSchedules()).hasSize(1);
    }

    @Test
    void createDoctorShouldFailWhenSpecialtyDoesNotExist() {
        UUID personId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        LocalDate laborStart = LocalDate.now();
        LocalDate laborEnd = LocalDate.now().plusDays(10);

        when(specialtyRepository.findById(SpecialtyCode.FISIOTERAPIA)).thenReturn(Optional.empty());

        CreateDoctorRequest request = new CreateDoctorRequest(
                List.of(SpecialtyCode.FISIOTERAPIA),
                laborStart,
                laborEnd,
                20,
                4,
                null
        );

        assertThrows(DoctorInvalidSpecialty.class, () -> doctorService.createDoctor(personId, request));

        verify(personExternalService, never()).deactivateUser(personId);
        verify(doctorRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getSpecialtiesShouldRestrictNewPatientToGeneralMedicine() {
        UUID patientId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        when(doctorRepository.findAllDistinctSpecialtyCodesByActiveDoctors())
                .thenReturn(List.of("MEDICINA_GENERAL", "FISIOTERAPIA"));
        when(appointmentExternalService.isNewPatient(patientId)).thenReturn(true);

        List<SpecialtyCode> result = doctorService.getSpecialties(patientId);

        assertThat(result).containsExactly(SpecialtyCode.MEDICINA_GENERAL);
    }

    @Test
    void updateDoctorAppointmentIntervalShouldRejectIntervalLargerThanEverySchedule() {
        UUID doctorId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Doctor doctor = new Doctor(doctorId, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10), 4, true, 20);
        doctor.setSchedules(new HashSet<>(Set.of(
                new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(8, 30), Workday.LUNES)
        )));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));

        assertThrows(IllegalArgumentException.class, () -> doctorService.updateDoctorAppointmentInterval(doctorId, 60));

        verify(doctorRepository, never()).save(doctor);
    }

    @Test
    void changeSpecialtiesShouldReplaceDoctorSpecialtiesWithCatalogEntries() {
        UUID doctorId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Doctor doctor = new Doctor(doctorId, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10), 4, true, 20);

        Specialty medicinaGeneral = new Specialty();
        medicinaGeneral.setCode(SpecialtyCode.MEDICINA_GENERAL);
        medicinaGeneral.setName("Medicina general");

        Specialty fisioterapia = new Specialty();
        fisioterapia.setCode(SpecialtyCode.FISIOTERAPIA);
        fisioterapia.setName("Fisioterapia");

        doctor.setSpecialties(new HashSet<>(Set.of(medicinaGeneral)));

        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor));
        when(specialtyRepository.findAllById(List.of(SpecialtyCode.FISIOTERAPIA)))
                .thenReturn(List.of(fisioterapia));

        doctorService.changeSpecialties(doctorId, List.of(SpecialtyCode.FISIOTERAPIA));

        assertThat(doctor.getSpecialties()).containsExactly(fisioterapia);
    }
}