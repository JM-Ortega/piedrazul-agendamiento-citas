package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateTimeOffRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import co.edu.unicauca.piedrazul.backend.doctors.exception.*;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorTimeOffRepository;
import co.edu.unicauca.piedrazul.backend.doctors.events.TimeOffChangedEvent;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimeOffServiceTest {

    private static final int WINDOW_WEEKS = 4;

    @Mock
    private DoctorRepository doctorRepository;
    @Mock
    private DoctorTimeOffRepository timeOffRepository;
    @Mock
    private AppointmentExternalService appointmentExternalService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SecurityContextExtractor securityExtractor;

    private TimeOffService service;
    private final UUID doctorId = UUID.randomUUID();
    private final LocalDate today = LocalDate.now();
    private final LocalDate windowEnd = today.plusWeeks(WINDOW_WEEKS);

    @BeforeEach
    void setUp() {
        service = new TimeOffService(doctorRepository, timeOffRepository, appointmentExternalService,
                eventPublisher, securityExtractor);
    }

    private Doctor doctor(boolean active, LocalDate laborEnd) {
        return new Doctor(doctorId, today.minusMonths(6), laborEnd, WINDOW_WEEKS, active, 30);
    }

    private void givenDoctor(Doctor doctor) {
        when(doctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.of(doctor));
    }

    private CreateTimeOffRequest request(LocalDate start, LocalDate end) {
        return new CreateTimeOffRequest(doctorId, start, end, "Vacaciones");
    }

    // ---------------- create ----------------

    @Test
    void createShouldSaveWhenStartsRightAfterBookingWindow() {
        givenDoctor(doctor(true, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(1);
        when(appointmentExternalService.findScheduledAppointmentDates(doctorId, start, start.plusDays(9)))
                .thenReturn(List.of());
        when(timeOffRepository.save(any(DoctorTimeOff.class))).thenAnswer(i -> i.getArgument(0));

        DoctorTimeOff saved = service.create(request(start, start.plusDays(9)));

        assertThat(saved.getDoctorId()).isEqualTo(doctorId);
        assertThat(saved.getStartDate()).isEqualTo(start);
        assertThat(saved.getEndDate()).isEqualTo(start.plusDays(9));
    }

    @Test
    void createShouldRejectWhenStartIsLastDayOfBookingWindow() {
        givenDoctor(doctor(true, today.plusYears(1)));

        assertThatThrownBy(() -> service.create(request(windowEnd, windowEnd.plusDays(3))))
                .isInstanceOf(TimeOffWithinBookingWindowException.class);
        verify(timeOffRepository, never()).save(any());
    }

    @Test
    void createShouldRejectWhenStartIsInsideBookingWindow() {
        givenDoctor(doctor(true, today.plusYears(1)));

        assertThatThrownBy(() -> service.create(request(today.plusDays(1), windowEnd.plusDays(10))))
                .isInstanceOf(TimeOffWithinBookingWindowException.class);
    }

    @Test
    void createShouldRejectEndBeforeStart() {
        givenDoctor(doctor(true, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(5);

        assertThatThrownBy(() -> service.create(request(start, start.minusDays(1))))
                .isInstanceOf(DateConflictException.class);
    }

    @Test
    void createShouldRejectInactiveDoctor() {
        givenDoctor(doctor(false, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(1);

        assertThatThrownBy(() -> service.create(request(start, start.plusDays(3))))
                .isInstanceOf(InactiveDoctorTimeOffException.class);
        verifyNoInteractions(timeOffRepository);
    }

    @Test
    void createShouldRejectWhenEndPassesLaborEnd() {
        LocalDate laborEnd = windowEnd.plusDays(10);
        givenDoctor(doctor(true, laborEnd));
        LocalDate start = windowEnd.plusDays(5);

        assertThatThrownBy(() -> service.create(request(start, laborEnd.plusDays(1))))
                .isInstanceOf(TimeOffExceedsLaborEndException.class);
    }

    @Test
    void createShouldAllowEndExactlyOnLaborEnd() {
        LocalDate laborEnd = windowEnd.plusDays(10);
        givenDoctor(doctor(true, laborEnd));
        LocalDate start = windowEnd.plusDays(5);
        when(appointmentExternalService.findScheduledAppointmentDates(any(), any(), any())).thenReturn(List.of());
        when(timeOffRepository.save(any(DoctorTimeOff.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.create(request(start, laborEnd)).getEndDate()).isEqualTo(laborEnd);
    }

    @Test
    void createShouldRejectWhenDoctorHasScheduledAppointmentsInRange() {
        givenDoctor(doctor(true, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(1);
        when(appointmentExternalService.findScheduledAppointmentDates(doctorId, start, start.plusDays(6)))
                .thenReturn(List.of(start.plusDays(2)));

        assertThatThrownBy(() -> service.create(request(start, start.plusDays(6))))
                .isInstanceOf(DoctorHasScheduledAppointments.class)
                .hasMessageContaining(start.plusDays(2).toString());
        verify(timeOffRepository, never()).save(any());
    }

    @Test
    void createShouldRejectOverlappingTimeOff() {
        givenDoctor(doctor(true, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(1);
        LocalDate end = start.plusDays(6);
        when(appointmentExternalService.findScheduledAppointmentDates(doctorId, start, end)).thenReturn(List.of());
        when(timeOffRepository.existsByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(doctorId, end, start))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request(start, end)))
                .isInstanceOf(TimeOffOverlapException.class);
        verify(timeOffRepository, never()).save(any());
    }

    @Test
    void createShouldFailWhenDoctorDoesNotExist() {
        when(doctorRepository.findByIdForUpdate(doctorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(windowEnd.plusDays(1), windowEnd.plusDays(3))))
                .isInstanceOf(DoctorNotFoundException.class);
    }

    // ---------------- delete ----------------

    @Test
    void deleteShouldRemoveTimeOffThatHasNotStarted() {
        UUID id = UUID.randomUUID();
        DoctorTimeOff timeOff = new DoctorTimeOff(doctorId, today.plusDays(40), today.plusDays(46), null);
        when(timeOffRepository.findById(id)).thenReturn(Optional.of(timeOff));

        service.delete(id);

        verify(timeOffRepository).delete(timeOff);
    }

    @Test
    void deleteShouldRemoveTimeOffThatStartsToday() {
        UUID id = UUID.randomUUID();
        DoctorTimeOff timeOff = new DoctorTimeOff(doctorId, today, today.plusDays(5), null);
        when(timeOffRepository.findById(id)).thenReturn(Optional.of(timeOff));

        service.delete(id);

        verify(timeOffRepository).delete(timeOff);
    }

    @Test
    void deleteShouldTruncateTimeOffInProgressAndFreeToday() {
        UUID id = UUID.randomUUID();
        DoctorTimeOff timeOff = new DoctorTimeOff(doctorId, today.minusDays(2), today.plusDays(3), null);
        when(timeOffRepository.findById(id)).thenReturn(Optional.of(timeOff));

        service.delete(id);

        assertThat(timeOff.getEndDate()).isEqualTo(today.minusDays(1));
        assertThat(timeOff.covers(today)).isFalse();
        verify(timeOffRepository).save(timeOff);
        verify(timeOffRepository, never()).delete(any(DoctorTimeOff.class));
    }

    @Test
    void deleteShouldRejectTimeOffThatAlreadyEnded() {
        UUID id = UUID.randomUUID();
        when(timeOffRepository.findById(id))
                .thenReturn(Optional.of(new DoctorTimeOff(doctorId, today.minusDays(10), today.minusDays(1), null)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(TimeOffAlreadyEndedException.class);
        verify(timeOffRepository, never()).save(any());
        verify(timeOffRepository, never()).delete(any(DoctorTimeOff.class));
    }

    @Test
    void deleteShouldFailWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(timeOffRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(TimeOffNotFoundException.class);
    }

    // ---------------- list ----------------

    @Test
    void getByDoctorShouldReturnHistoryFromRepository() {
        when(doctorRepository.findById(doctorId)).thenReturn(Optional.of(doctor(true, today.plusYears(1))));
        List<DoctorTimeOff> history = List.of(new DoctorTimeOff(doctorId, today.minusDays(9), today.minusDays(3), null));
        when(timeOffRepository.findByDoctorIdOrderByStartDateDesc(doctorId)).thenReturn(history);

        assertThat(service.getByDoctor(doctorId)).isEqualTo(history);
    }

    // ---------------- auditoría ----------------

    private TimeOffChangedEvent publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return (TimeOffChangedEvent) captor.getValue();
    }

    @Test
    void createShouldPublishCreatedEventWithRangeAndActor() {
        givenDoctor(doctor(true, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(1);
        LocalDate end = start.plusDays(4);
        when(appointmentExternalService.findScheduledAppointmentDates(doctorId, start, end)).thenReturn(List.of());
        when(timeOffRepository.save(any(DoctorTimeOff.class))).thenAnswer(i -> i.getArgument(0));
        when(securityExtractor.currentActorId()).thenReturn("admin-1");
        when(securityExtractor.currentActorRoles()).thenReturn("[ADMIN]");

        service.create(request(start, end));

        TimeOffChangedEvent event = publishedEvent();
        assertThat(event.change()).isEqualTo(TimeOffChangedEvent.Change.CREATED);
        assertThat(event.doctorId()).isEqualTo(doctorId.toString());
        assertThat(event.performedBy()).isEqualTo("admin-1");
        assertThat(event.performedByRole()).isEqualTo("[ADMIN]");
        assertThat(event.beforeState()).isNull();
        assertThat(event.afterState()).isEqualTo("{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}");
    }

    @Test
    void createShouldNotPublishWhenValidationFails() {
        givenDoctor(doctor(false, today.plusYears(1)));
        LocalDate start = windowEnd.plusDays(1);

        assertThatThrownBy(() -> service.create(request(start, start.plusDays(3))))
                .isInstanceOf(InactiveDoctorTimeOffException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void deleteShouldPublishDeletedEventWhenTimeOffHasNotStarted() {
        UUID id = UUID.randomUUID();
        LocalDate start = today.plusDays(40);
        LocalDate end = today.plusDays(46);
        when(timeOffRepository.findById(id)).thenReturn(Optional.of(new DoctorTimeOff(doctorId, start, end, null)));

        service.delete(id);

        TimeOffChangedEvent event = publishedEvent();
        assertThat(event.change()).isEqualTo(TimeOffChangedEvent.Change.DELETED);
        assertThat(event.beforeState()).contains(start.toString()).contains(end.toString());
        assertThat(event.afterState()).isNull();
    }

    @Test
    void deleteShouldPublishTruncatedEventWithOldAndNewEnd() {
        UUID id = UUID.randomUUID();
        LocalDate start = today.minusDays(2);
        LocalDate end = today.plusDays(3);
        when(timeOffRepository.findById(id)).thenReturn(Optional.of(new DoctorTimeOff(doctorId, start, end, null)));

        service.delete(id);

        TimeOffChangedEvent event = publishedEvent();
        assertThat(event.change()).isEqualTo(TimeOffChangedEvent.Change.TRUNCATED);
        assertThat(event.beforeState()).contains(end.toString());
        assertThat(event.afterState()).contains(today.minusDays(1).toString()).doesNotContain(end.toString());
    }

    @Test
    void deleteShouldNotPublishWhenTimeOffAlreadyEnded() {
        UUID id = UUID.randomUUID();
        when(timeOffRepository.findById(id))
                .thenReturn(Optional.of(new DoctorTimeOff(doctorId, today.minusDays(10), today.minusDays(1), null)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(TimeOffAlreadyEndedException.class);

        verifyNoInteractions(eventPublisher);
    }
}
