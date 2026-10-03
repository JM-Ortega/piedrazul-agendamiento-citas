package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateTimeOffRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import co.edu.unicauca.piedrazul.backend.doctors.exception.*;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorTimeOffRepository;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class TimeOffService {

    private final DoctorRepository doctorRepository;
    private final DoctorTimeOffRepository timeOffRepository;
    private final AppointmentExternalService appointmentExternalService;

    public TimeOffService(
            DoctorRepository doctorRepository,
            DoctorTimeOffRepository timeOffRepository,
            AppointmentExternalService appointmentExternalService
    ) {
        this.doctorRepository = doctorRepository;
        this.timeOffRepository = timeOffRepository;
        this.appointmentExternalService = appointmentExternalService;
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

        return timeOffRepository.save(new DoctorTimeOff(doctor.getPersonId(), start, end, request.reason()));
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

        if (timeOff.isUpcoming(today)) {
            timeOffRepository.delete(timeOff);
        } else {
            timeOff.endBefore(today);
            timeOffRepository.save(timeOff);
        }
    }

    private Doctor findDoctor(UUID doctorId) {
        return doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));
    }
}
