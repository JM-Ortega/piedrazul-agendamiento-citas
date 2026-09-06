package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingSchedule;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.proyections.DoctorSpecialtyProjection;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import de.focus_shift.jollyday.core.HolidayManager;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.time.DayOfWeek;
import java.util.stream.Collectors;

// Servicio para peticiones externas
public class DoctorExternalServiceImpl implements DoctorExternalService {

    private final DoctorRepository doctorRepository;
    private final ScheduleService scheduleService;
    private final PersonExternalService personExternalService;
    private final HolidayManager holidayManager;

    public DoctorExternalServiceImpl(
            DoctorRepository doctorRepository,
            ScheduleService scheduleService,
            PersonExternalService personExternalService,
            HolidayManager holidayManager
    ) {
        this.doctorRepository = doctorRepository;
        this.scheduleService = scheduleService;
        this.personExternalService = personExternalService;
        this.holidayManager = holidayManager;
    }

    @Override
    public Optional<UUID> findByUserId(UUID userId) {
        return personExternalService.findByUserId(userId)
                .filter(person -> doctorRepository.existsById(person.id()))
                .map(PersonSummary::id);
    }

    @Override
    public int getIntervalMinutesByDoctor(UUID idDoctor) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        return doctor.getAppointmentInterval();
    }

    @Override
    public List<UUID> getActiveDoctorIds() {
        return doctorRepository.findByStatusTrue()
                .stream()
                .map(Doctor::getPersonId)
                .toList();
    }

    @Override
    @Transactional
    public List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds) {
        if (doctorIds == null || doctorIds.isEmpty()) {
            return List.of();
        }

        List<Doctor> doctors = doctorRepository.findByPersonIdIn(doctorIds);

        Map<UUID, String> names = personExternalService.getPersonNames(doctorIds);

        return doctors.stream()
                .map(d -> DoctorResponse.fromEntity(d, names.get(d.getPersonId())))
                .toList();
    }

    @Override
    public Map<UUID, List<SpecialtyCode>> findSpecialtiesByPersonIds(Collection<UUID> personIds){

        List<DoctorSpecialtyProjection> projections = doctorRepository.findSpecialtiesByPersonIds(personIds);

        return projections.stream()
                .collect(Collectors.groupingBy(
                        DoctorSpecialtyProjection::personId,
                        Collectors.mapping(
                                DoctorSpecialtyProjection::specialty,
                                Collectors.toList()
                        )
                ));
    }

    @Override
    public Map<UUID, Integer> bookingWindowWeeksByDoctorIds(List<UUID> doctorIds){
        return doctorRepository.findAllById(doctorIds).stream()
                .collect(Collectors.toMap(Doctor::getPersonId, Doctor::getBookingWindowWeeks));
    }

    @Override
    public Map<UUID, Integer> intervalMinutesByDoctorIds(List<UUID> doctorIds) {
        return doctorRepository.findAllById(doctorIds).stream()
                .collect(Collectors.toMap(Doctor::getPersonId, Doctor::getAppointmentInterval));
    }

    @Override
    public WorkingSchedule workingSchedule(UUID idDoctor){
        Doctor doctor = doctorRepository.findByPersonId(idDoctor);

        return new WorkingSchedule(workingDatesAndSlots(doctor),doctor.getAppointmentInterval());
    }

    private List<WorkingDateSlots> workingDatesAndSlots(Doctor doctor) {

        // Horarios del médico agrupados por día
        Map<DayOfWeek, List<Schedule>> schedulesByDay =
                doctor.getSchedules().stream()
                        .collect(Collectors.groupingBy(
                                schedule -> schedule.getWorkday().toDayOfWeek()
                        ));

        LocalDate inicio = LocalDate.now();

        // Límite de agendamiento
        LocalDate limiteAgendamiento =
                inicio.plusWeeks(doctor.getBookingWindowWeeks());

        if (limiteAgendamiento.isAfter(doctor.getLaborEnd())) {
            limiteAgendamiento = doctor.getLaborEnd();
        }

        if (!inicio.isBefore(limiteAgendamiento.plusDays(1)) || inicio.isBefore(doctor.getLaborStart())) {
                return List.of();
        }

        // Fechas disponibles quitando festivos y dias diferentes a los de atención
        List<LocalDate> fechas = inicio
                .datesUntil(limiteAgendamiento.plusDays(1))
                .filter(fecha ->
                        schedulesByDay.containsKey(fecha.getDayOfWeek()))
                .filter(fecha ->
                        !holidayManager.isHoliday(fecha))
                .toList();

        // Generar slots para cada fecha
        return fechas.stream()
                .map(fecha -> {

                    List<LocalTime> slots = schedulesByDay
                            .get(fecha.getDayOfWeek())
                            .stream()
                            .flatMap(schedule ->
                                    generateSlots(
                                            schedule.getStartTime(),
                                            schedule.getEndTime(),
                                            doctor.getAppointmentInterval()
                                    ).stream()
                            )
                            .sorted()
                            .toList();

                    return new WorkingDateSlots(fecha, slots);
                })
                .toList();
    }

    private List<LocalTime> generateSlots(
            LocalTime startTime,
            LocalTime endTime,
            int interval
    ) {
        List<LocalTime> slots = new ArrayList<>();

        LocalTime current = startTime;

        while (!current.plusMinutes(interval).isAfter(endTime)) {
            slots.add(current);
            current = current.plusMinutes(interval);
        }

        return slots;
    }

    private static Workday toWorkday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY    -> Workday.LUNES;
            case TUESDAY   -> Workday.MARTES;
            case WEDNESDAY -> Workday.MIERCOLES;
            case THURSDAY  -> Workday.JUEVES;
            case FRIDAY    -> Workday.VIERNES;

            default -> throw new IllegalArgumentException("Día no laboral o inesperado: " + dayOfWeek);
        };
    }
}
