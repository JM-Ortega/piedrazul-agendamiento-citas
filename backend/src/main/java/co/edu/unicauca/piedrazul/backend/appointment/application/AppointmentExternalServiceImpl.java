package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.exception.NoAvailableDoctorsException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableDatesAndSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorsAvailability;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.ScheduleAvailability;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// No es un USECASE
@Service
public class AppointmentExternalServiceImpl implements AppointmentExternalService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final GetAvailableDatesAndSlotsUseCase getAvailableDatesAndSlotsUseCase;
    private final IsNewPatientUseCase isNewPatientUseCase;
    private final PatientConsultPort patientConsultPort;
    private final BusySlotService busySlotService;

    public AppointmentExternalServiceImpl(AppointmentRepository appointmentRepository, DoctorConfigConsultPort doctorConfigConsultPort,
                                          GetAvailableDatesAndSlotsUseCase getAvailableDatesAndSlotsUseCase, IsNewPatientUseCase isNewPatientUseCase,
                                          PatientConsultPort patientConsultPort, BusySlotService busySlotService) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.getAvailableDatesAndSlotsUseCase = getAvailableDatesAndSlotsUseCase;
        this.isNewPatientUseCase = isNewPatientUseCase;
        this.patientConsultPort = patientConsultPort;
        this.busySlotService = busySlotService;
    }

    @Override
    public AppointmentExternalData getAppointmentData(UUID idAppointment) {

        Appointment appointment = appointmentRepository.findById(idAppointment);
        String doctorName = doctorConfigConsultPort.getDoctorName(appointment.getIdDoctor());
        return new AppointmentExternalData(
                appointment.getIdAppointment(),
                appointment.getIdDoctor(),
                doctorName,
                appointment.getIdPatient(),
                appointment.getAppointmentState().name(),
                appointment.getDate()
        );
    }

    @Override
    public List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state){

        List<Appointment> appointments = (state != null)
                ? appointmentRepository.findByDoctorIdAndDateAndState(idDoctor, date, state)
                : appointmentRepository.findByDoctorIdAndDate(idDoctor, date); // trae todas

        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);

        // Muchos pacientes distintos — búsqueda en lote, una sola consulta para todos
        Set<UUID> patientIds = appointments.stream().map(Appointment::getIdPatient).collect(Collectors.toSet());
        Map<UUID, PatientInfo> patientsById = patientConsultPort.findByIds(patientIds);

        return appointments.stream()
                .map(a -> {
                    PatientInfo patient = patientsById.get(a.getIdPatient());
                    return new AppointmentSummary(
                            a.getIdAppointment(),
                            a.getIdPatient(),
                            patient.getFirstName() + " " + patient.getLastName(),
                            patient.getDocumentNumber(),
                            patient.getPhone(),
                            a.getIdDoctor(),
                            doctorName,
                            a.getDate(),
                            a.getStartTime().getTime(),
                            a.getSpecialty().name(),
                            a.getAppointmentState().name()
                    );
                }).toList();

    }

    @Override
    public UUID getPattientIdByAppointmentId(UUID appointmentId){
        return appointmentRepository.getPattientIdByAppointmentId(appointmentId);
    }

    @Override
    public List<SchedulerAppointmentSummary> findAllByDate(LocalDate date) {
        List<Appointment> appointments = appointmentRepository.findAllByDate(date)
                .stream()
                .filter(a -> a.getAppointmentState() == AppointmentState.AGENDADA)
                .toList();

        // Aquí sí hay múltiples doctores distintos — usa el método en lote que YA existe en el puerto
        Set<UUID> doctorIds = appointments.stream().map(Appointment::getIdDoctor).collect(Collectors.toSet());
        Map<UUID, String> doctorNamesById = doctorConfigConsultPort.getDoctorInfoByIds(doctorIds.stream().toList())
                .stream()
                .collect(Collectors.toMap(DoctorResponse::id, DoctorResponse::name)); // ajustar a los getters reales

        Set<UUID> patientIds = appointments.stream().map(Appointment::getIdPatient).collect(Collectors.toSet());
        Map<UUID, PatientInfo> patientsById = patientConsultPort.findByIds(patientIds);

        return appointments.stream()
                .map(a -> {
                    PatientInfo patient = patientsById.get(a.getIdPatient());
                    return new SchedulerAppointmentSummary(
                            doctorNamesById.get(a.getIdDoctor()),
                            patient.getFirstName() + " " + patient.getLastName(),
                            a.getStartTime().getTime()
                    );
                }).toList();
    }

    @Override
    public boolean hasAvailableSlots(LocalDate date) {

        if (date.isBefore(LocalDate.now())) {
            return false;
        }

        List<UUID> idsActiveDoctors =
                doctorConfigConsultPort.getActiveDoctorIds();

        for (UUID idDoctor : idsActiveDoctors) {

            List<AvailableDateSlots> availableDatesAndSlots =
                    getAvailableDatesAndSlotsUseCase
                            .getAvailableDatesAndSlots(idDoctor);

            if (availableDatesAndSlots.stream()
                    .anyMatch(dateSlots -> dateSlots.date().equals(date))) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean hasScheduledAppointments(UUID doctorID){
        List<Appointment> appointments = appointmentRepository.findByDoctorIdAndState(doctorID, "AGENDADA");
        return !appointments.isEmpty();
    }

    @Override
    public boolean isNewPatient(UUID patientId){
        return isNewPatientUseCase.isNewPatient(patientId);
    }

    @Override
    public Set<UUID> calculateDoctorsAvailability(
            List<DoctorsAvailability> doctorsAvailability
    ) {
        Set<UUID> availableDoctors = doctorsAvailability.stream()
                .filter(this::hasAvailableSlot)
                .map(DoctorsAvailability::personId)
                .collect(Collectors.toSet());

        if (availableDoctors.isEmpty()) {
            throw new NoAvailableDoctorsException(
                    "No hay medicos con espacios disponibles para el agendamiento."
            );
        }

        return availableDoctors;
    }

    //Auxiliares

    private boolean hasAvailableSlot(DoctorsAvailability doctor) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusWeeks(doctor.bookingWindowWeeks());

        return from.datesUntil(to.plusDays(1))
                .filter(this::isWeekday)
                .anyMatch(date -> hasAvailableSlotForDay(doctor, date));
    }

    private boolean hasAvailableSlotForDay(
            DoctorsAvailability doctor,
            LocalDate date
    ) {
        List<Appointment> appointments =
                appointmentRepository.findByDoctorIdAndDate(
                        doctor.personId(),
                        date
                );

        return doctor.schedules().stream()
                .filter(schedule -> isScheduleForDate(schedule, date))
                .anyMatch(schedule -> hasAvailableSlotInSchedule(
                        schedule,
                        appointments,
                        doctor.appointmentInterval()
                ));
    }

    private boolean hasAvailableSlotInSchedule(
            ScheduleAvailability schedule,
            List<Appointment> appointments,
            int interval
    ) {
        LocalTime current = schedule.startTime();

        // Porque el la fecha final es el fin de todo poojemplo si es 12:00 no hay mas turnos a partir de ahi
        // no es valida una cita hasta las 12:30 por lo tanto si el intervalo es de 09:00 - 12:00 y el intervalo
        // de atención es de 30 min, solo se muestran slots hasta las 11:30 para que se acabe la jornada a las 12:00
        while (!current.isAfter(schedule.endTime())) {
            AppointmentTime slot =
                    AppointmentTime.withoutBusinessHoursRestriction(current);

            if (!busySlotService.isBusy(appointments, slot, interval)) {
                return true;
            }

            current = current.plusMinutes(interval);
        }

        return false;
    }

    private boolean isScheduleForDate(
            ScheduleAvailability schedule,
            LocalDate date
    ) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> schedule.workday() == Workday.LUNES;
            case TUESDAY -> schedule.workday() == Workday.MARTES;
            case WEDNESDAY -> schedule.workday() == Workday.MIERCOLES;
            case THURSDAY -> schedule.workday() == Workday.JUEVES;
            case FRIDAY -> schedule.workday() == Workday.VIERNES;
            default -> false;
        };
    }

    private boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
