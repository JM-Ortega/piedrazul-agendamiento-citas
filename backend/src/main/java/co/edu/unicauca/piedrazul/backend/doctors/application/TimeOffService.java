package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateTimeOffRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import co.edu.unicauca.piedrazul.backend.doctors.events.TimeOffChangedEvent;
import co.edu.unicauca.piedrazul.backend.doctors.exception.*;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorTimeOffRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import jakarta.transaction.Transactional;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class TimeOffService {

    private final DoctorRepository doctorRepository;
    private final DoctorTimeOffRepository timeOffRepository;
    private final AppointmentExternalService appointmentExternalService;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityContextExtractor securityExtractor;

    public TimeOffService(
            DoctorRepository doctorRepository,
            DoctorTimeOffRepository timeOffRepository,
            AppointmentExternalService appointmentExternalService,
            ApplicationEventPublisher eventPublisher,
            SecurityContextExtractor securityExtractor
    ) {
        this.doctorRepository = doctorRepository;
        this.timeOffRepository = timeOffRepository;
        this.appointmentExternalService = appointmentExternalService;
        this.eventPublisher = eventPublisher;
        this.securityExtractor = securityExtractor;
    }

    @Transactional
    public DoctorTimeOff create(CreateTimeOffRequest request) {
        // Con lock sobre el doctor: dos descansos simultáneos no pueden pasar la validación de solapamiento a la vez
        Doctor doctor = doctorRepository.findByIdForUpdate(request.doctorId())
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));
        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        LocalDate today = LocalDate.now();

        if (end.isBefore(start)) {
            throw new DateConflictException("La fecha de fin del descanso no puede ser anterior a la de inicio");
        }

        if (!doctor.isStatus()) {
            throw new InactiveDoctorTimeOffException("No se pueden registrar descansos a un doctor inactivo");
        }

        // Fin de la ventana de agendamiento: ese último día todavía se puede agendar
        LocalDate bookingWindowEnd = today.plusWeeks(doctor.getBookingWindowWeeks());
        if (!start.isAfter(bookingWindowEnd)) {
            throw new TimeOffWithinBookingWindowException(
                    "El descanso debe empezar después de " + bookingWindowEnd
                            + ", último día disponible para agendar con este doctor");
        }

        if (end.isAfter(doctor.getLaborEnd())) {
            throw new TimeOffExceedsLaborEndException(
                    "El descanso no puede terminar después de la fecha de fin de vinculación del doctor ("
                            + doctor.getLaborEnd() + ")");
        }

        List<LocalDate> appointmentDates =
                appointmentExternalService.findScheduledAppointmentDates(doctor.getPersonId(), start, end);
        if (!appointmentDates.isEmpty()) {
            throw new DoctorHasScheduledAppointments(
                    "El doctor tiene " + appointmentDates.size() + " cita(s) agendada(s) en esas fechas: "
                            + appointmentDates.stream().distinct().map(LocalDate::toString)
                            .collect(Collectors.joining(", ")));
        }

        if (timeOffRepository.existsByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                doctor.getPersonId(), end, start)) {
            throw new TimeOffOverlapException("El rango se cruza con otro descanso del doctor");
        }

        DoctorTimeOff saved = timeOffRepository.save(
                new DoctorTimeOff(doctor.getPersonId(), start, end, request.reason()));
        publish(saved.getDoctorId(), TimeOffChangedEvent.Change.CREATED, null, rangeJson(start, end));
        return saved;
    }

    public List<DoctorTimeOff> getByDoctor(UUID doctorId) {
        findDoctor(doctorId);
        return timeOffRepository.findByDoctorIdOrderByStartDateDesc(doctorId);
    }

    /**
     * Si el descanso aún no empezó se borra; si está en curso se recorta para que hoy y los días
     * siguientes queden libres y los ya descansados queden como historial; si terminó se rechaza.
     */
    @Transactional
    public void delete(UUID timeOffId) {
        DoctorTimeOff timeOff = timeOffRepository.findById(timeOffId)
                .orElseThrow(() -> new TimeOffNotFoundException("Descanso no encontrado"));
        LocalDate today = LocalDate.now();

        if (timeOff.hasEnded(today)) {
            throw new TimeOffAlreadyEndedException("El descanso ya terminó y se conserva como historial");
        }

        String before = rangeJson(timeOff.getStartDate(), timeOff.getEndDate());
        if (timeOff.isUpcoming(today)) {
            timeOffRepository.delete(timeOff);
            publish(timeOff.getDoctorId(), TimeOffChangedEvent.Change.DELETED, before, null);
        } else {
            timeOff.endBefore(today);
            timeOffRepository.save(timeOff);
            publish(timeOff.getDoctorId(), TimeOffChangedEvent.Change.TRUNCATED, before,
                    rangeJson(timeOff.getStartDate(), timeOff.getEndDate()));
        }
    }

    private void publish(UUID doctorId, TimeOffChangedEvent.Change change, String before, String after) {
        eventPublisher.publishEvent(new TimeOffChangedEvent(
                doctorId.toString(),
                change,
                securityExtractor.currentActorId(),
                securityExtractor.currentActorRoles(),
                MDC.get("correlationId"),
                before,
                after));
    }

    private static String rangeJson(LocalDate start, LocalDate end) {
        return "{\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}";
    }

    private Doctor findDoctor(UUID doctorId) {
        return doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));
    }
}
