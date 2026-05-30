package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;


public class Appointment {
    private final UUID idAppointment;
    private final UUID idDoctor;
    private final String doctorName;
    private final UUID idPatient;
    private final String patientName;
    private final PatientInfo  patientInfo;
    private final Specialty specialty;
    private AppointmentState appointmentState;
    private final LocalDate date;
    private final AppointmentTime startTime;
    private final SchedulingOrigin schedulingOrigin;

    // Constructor privado — solo accesible desde los factory methods
    private Appointment(UUID idAppointment,
                        UUID idDoctor,
                        String doctorName,
                        UUID idPatient,
                        String patientName,
                        PatientInfo patientInfo,
                        Specialty specialty,
                        LocalDate date,
                        AppointmentTime startTime,
                        SchedulingOrigin schedulingOrigin) {
        this.idAppointment = idAppointment;
        this.idDoctor = idDoctor;
        this.doctorName = doctorName;
        this.idPatient = idPatient;
        this.patientName = patientName;
        this.patientInfo = patientInfo;
        this.specialty = specialty;
        this.date = date;
        this.startTime = startTime;
        this.schedulingOrigin = schedulingOrigin;

        // Siempre inicia en AGENDADA
        this.appointmentState   = AppointmentState.AGENDADA;
    }

    // Factory Method 1
    // El agendador pasa los datos crudos del paciente
    // pacienteId es null porque el paciente no tiene cuenta aún
    public static Appointment scheduleManual(String doctorName,
                                             UUID idDoctor,
                                             UUID idPatient,
                                             String patientName,
                                             PatientInfo patientInfo,
                                             Specialty specialty,
                                             LocalDate date,
                                             AppointmentTime startTime) {
        Objects.requireNonNull(idDoctor,"El médico es obligatorio");
        Objects.requireNonNull(doctorName, "El nombre del médico es obligatorio");
        Objects.requireNonNull(patientInfo, "Los datos del paciente son obligatorios");
        Objects.requireNonNull(specialty, "La especialidad es obligatoria");
        Objects.requireNonNull(date, "La fecha es obligatoria");
        Objects.requireNonNull(startTime, "La hora es obligatoria");

        return new Appointment(
                //Se genera automáticamente por la anotación jpa si también lo hacemos aquí genera fallos
                null,
                idDoctor,
                doctorName,
                idPatient,
                patientName,
                patientInfo,
                specialty,
                date,
                startTime,
                SchedulingOrigin.MANUAL
        );
    }

    // Factory Method 2
    // El paciente web agenda de forma autónoma entonces el idPatient siempre debe estar presente
    public static Appointment scheduleAutonomous(String doctorName,
                                                 UUID idDoctor,
                                                 UUID idPatient,
                                                 String patientName,
                                                 PatientInfo patientInfo,
                                                 Specialty specialty,
                                                 LocalDate date,
                                                 AppointmentTime startTime) {
        Objects.requireNonNull(idDoctor, "El médico es obligatorio");
        Objects.requireNonNull(doctorName, "El nombre del médico es obligatorio");
        Objects.requireNonNull(idPatient, "El pacienteId es obligatorio en agendamiento autónomo");
        Objects.requireNonNull(specialty, "La especialidad es obligatoria");
        Objects.requireNonNull(date, "La fecha es obligatoria");
        Objects.requireNonNull(startTime, "La hora es obligatoria");

        return new Appointment(
                //Se genera automáticamente por la anotación jpa si también lo hacemos aquí genera fallos
                null,
                idDoctor,
                doctorName,
                idPatient,
                patientName,
                patientInfo,
                specialty,
                date,
                startTime,
                SchedulingOrigin.AUTONOMO
        );
    }

    public static Appointment reconstruct (UUID idAppointment,
                                          UUID idDoctor,
                                          String doctorName,
                                          UUID idPatient,
                                           String patientName,
                                          PatientInfo patientInfo,
                                          Specialty specialty,
                                          AppointmentState appointmentState,
                                          LocalDate date,
                                          AppointmentTime startTime,
                                          SchedulingOrigin schedulingOrigin) {

        // Usa el constructor privado igual que los otros factory methods
        Appointment appointment = new Appointment(
                idAppointment,
                idDoctor,
                doctorName,
                idPatient,
                patientName,
                patientInfo,
                specialty,
                date,
                startTime,
                schedulingOrigin
        );

        // Sobreescribe el estado con el que viene de la BD
        // porque el constructor siempre pone AGENDADA
        appointment.appointmentState = appointmentState;

        return appointment;
    }

    public void markAsNoShow() {
        if (this.appointmentState != AppointmentState.AGENDADA) {
            throw new IllegalStateException(
                    "Solo las citas AGENDADAS pueden marcarse como NO_ASISTIO"
            );
        }
        this.appointmentState = AppointmentState.NO_ASISTIO;
    }

    public UUID getIdAppointment() {
        return idAppointment;
    }

    public SchedulingOrigin getSchedulingOrigin() {
        return schedulingOrigin;
    }

    public AppointmentTime getStartTime() {
        return startTime;
    }

    public LocalDate getDate() {
        return date;
    }

    public AppointmentState getAppointmentState() {
        return appointmentState;
    }

    public Specialty getSpecialty() {
        return specialty;
    }

    public PatientInfo getPatientInfo() {
        return patientInfo;
    }

    public String getPatientName() {
        return patientName;
    }

    public UUID getIdPatient() {
        return idPatient;
    }

    public String getDoctorName() {
        return doctorName;
    }

    public UUID getIdDoctor() {
        return idDoctor;
    }

    public void setAppointmentState(AppointmentState appointmentState) {
        this.appointmentState = appointmentState;
    }
}
