package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ScheduleAutonomousAppointmentUseCaseImpl implements ScheduleAutonomousAppointmentUseCase {
    private final AppointmentRepository appointmentRepository;
    private final PatientConsultPort patientConsultPort;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;

    public ScheduleAutonomousAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        this.appointmentRepository   = appointmentRepository;
        this.patientConsultPort      = patientConsultPort;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService      = appointmentService;
    }

    // Paciente agenda de forma autónoma por la web
    @Override
    public Appointment scheduleAutonomous(UUID idPatient, UUID idDoctor, Specialty specialty, LocalDate date, AppointmentTime startTime) {
        // 1. Obtiene datos del paciente a través del puerto de salida (módulo de pacientes)
        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);

        // 2. Obtiene configuración del médico a través del puerto de salida
        int intervalMinutes = doctorConfigConsultPort
                .getIntervalMinutesByDoctor(idDoctor);

        // 3. Obtiene citas existentes del médico ese día
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndDate(idDoctor, date);

        validateUniqueScheduledAppointmentBySpecialty(idPatient, specialty);
        validateNoTimeConflictForPatient(idPatient, date, startTime);

        // 4. Delega la lógica de negocio al servicio de dominio
        String patientName= patientConsultPort.findById(idPatient).getFirstName() + " " + patientConsultPort.findById(idPatient).getLastName();

        Appointment appointment = appointmentService.scheduleAutonomous(
                doctorName, idPatient, null, idDoctor, patientName, specialty,
                date, startTime, intervalMinutes, existingAppointments
        );

        // 5. Persiste a través del puerto de salida
        appointmentRepository.save(appointment);

        return appointment;
    }

    private void validateUniqueScheduledAppointmentBySpecialty(UUID idPatient, Specialty specialty) {
        boolean hasScheduledInSameSpecialty = appointmentRepository.findByPatientId(idPatient)
                .stream()
                .anyMatch(appointment -> appointment.getSpecialty() == specialty
                        && appointment.getAppointmentState() == AppointmentState.AGENDADA);

        if (hasScheduledInSameSpecialty) {
            throw new PatientAlreadyScheduledInSpecialtyException(
                    "El paciente ya tiene una cita AGENDADA para la especialidad " + specialty
            );
        }
    }

    private void validateNoTimeConflictForPatient(UUID idPatient, LocalDate date, AppointmentTime startTime) {
        boolean hasTimeConflict = appointmentRepository.findByPatientIdAndDate(idPatient, date)
                .stream()
                .anyMatch(appointment -> appointment.getStartTime().equals(startTime)
                        && appointment.getAppointmentState().isActive());

        if (hasTimeConflict) {
            throw new PatientScheduleTimeConflictException(
                    "El paciente ya tiene una cita activa para la fecha " + date + " a las " + startTime.getTime()
            );
        }
    }
}
