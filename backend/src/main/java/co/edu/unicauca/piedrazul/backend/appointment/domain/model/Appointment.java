package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
public class Appointment {
    private final UUID idAppointment;
    private final UUID idDoctor;
    private final UUID idPatient;
    private final PatientInfo  patientInfo;
    private final Specialty specialty;
    private AppointmentState appointmentState;
    private final LocalDate date;
    private final AppointmentTime startTime;
    private final SchedulingOrigin schedulingOrigin;

    // Constructor privado — solo accesible desde los factory methods
    private Appointment(UUID idAppointment,
                        UUID idDoctor,
                        UUID idPatient,
                        PatientInfo patientInfo,
                        Specialty specialty,
                        LocalDate date,
                        AppointmentTime startTime,
                        SchedulingOrigin schedulingOrigin) {
        this.idAppointment      = idAppointment;
        this.idDoctor           = idDoctor;
        this.idPatient          = idPatient;
        this.patientInfo        = patientInfo;
        this.specialty          = specialty;
        this.date               = date;
        this.startTime          = startTime;
        this.schedulingOrigin   = schedulingOrigin;

        // Siempre inicia en AGENDADA
        this.appointmentState   = AppointmentState.AGENDADA;
    }

    // Factory Method 1
    // El agendador pasa los datos crudos del paciente
    // pacienteId es null porque el paciente no tiene cuenta aún
    public static Appointment scheduleManual(UUID idDoctor,
                                             PatientInfo patientInfo,
                                             Specialty specialty,
                                             LocalDate date,
                                             AppointmentTime startTime) {
        Objects.requireNonNull(idDoctor,      "El médico es obligatorio");
        Objects.requireNonNull(patientInfo,   "Los datos del paciente son obligatorios");
        Objects.requireNonNull(specialty,     "La especialidad es obligatoria");
        Objects.requireNonNull(date,          "La fecha es obligatoria");
        Objects.requireNonNull(startTime,     "La hora es obligatoria");

        return new Appointment(
                UUID.randomUUID(),
                idDoctor,
                null,
                patientInfo,
                specialty,
                date,
                startTime,
                SchedulingOrigin.WHATSAPP
        );
    }

    // Factory Method 2
    // El paciente web agenda de forma autónoma entonces el idPatient siempre debe estar presente
    public static Appointment scheduleAutonomous(UUID idDoctor,
                                                 UUID idPatient,
                                                 PatientInfo patientInfo,
                                                 Specialty specialty,
                                                 LocalDate date,
                                                 AppointmentTime startTime) {
        Objects.requireNonNull(idDoctor,    "El médico es obligatorio");
        Objects.requireNonNull(idPatient,   "El pacienteId es obligatorio en agendamiento autónomo");
        Objects.requireNonNull(patientInfo, "Los datos del paciente son obligatorios");
        Objects.requireNonNull(specialty,   "La especialidad es obligatoria");
        Objects.requireNonNull(date,        "La fecha es obligatoria");
        Objects.requireNonNull(startTime,   "La hora es obligatoria");

        return new Appointment(
                UUID.randomUUID(),
                idDoctor,
                idPatient,
                patientInfo,
                specialty,
                date,
                startTime,
                SchedulingOrigin.WEB
        );
    }

    public boolean conflictsWith(AppointmentTime candidateTime, int intervalMinutes) {
        return this.startTime.conflictsWith(candidateTime, intervalMinutes);
    }

    public AppointmentState getAppointmentState() {
        return appointmentState;
    }

    public AppointmentTime getStartTime() {
        return startTime;
    }
}
