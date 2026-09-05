package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import de.focus_shift.jollyday.core.HolidayManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorExternalServiceImplTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private PersonExternalService personExternalService;

    @Mock
    private HolidayManager holidayManager;

    @Test
    void workingScheduleShouldReturnEmptyWhenBookingWindowHasExpired() {
        UUID doctorId = UUID.randomUUID();
        Doctor doctor = mock(Doctor.class);
        LocalDate today = LocalDate.now();

        when(doctorRepository.findByPersonId(doctorId)).thenReturn(doctor);
        when(doctor.getLaborStart()).thenReturn(today.minusWeeks(27));
        when(doctor.getLaborEnd()).thenReturn(today.plusMonths(1));
        when(doctor.getBookingWindowWeeks()).thenReturn(26);
        when(doctor.getAppointmentInterval()).thenReturn(30);
        when(doctor.getSchedules()).thenReturn(java.util.Set.of());

        DoctorExternalServiceImpl service = new DoctorExternalServiceImpl(
                doctorRepository,
                scheduleService,
                personExternalService,
                holidayManager
        );

        assertThat(service.workingSchedule(doctorId).datesAndSlots()).isEqualTo(List.of());
        verifyNoInteractions(holidayManager);
    }
}
