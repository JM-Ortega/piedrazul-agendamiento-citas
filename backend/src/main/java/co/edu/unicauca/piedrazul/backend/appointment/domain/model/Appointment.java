package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import static org.hibernate.action.internal.BulkOperationCleanupAction.schedule;


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
    public static Appointment scheduleManual(
            AppointmentSchedulingRequest request) {

        return schedule(
                request,
                SchedulingOrigin.MANUAL
        );
    }

    // Factory Method 2
    // El paciente web agenda de forma autónoma entonces el idPatient siempre debe estar presente
    public static Appointment scheduleAutonomous(
            AppointmentSchedulingRequest request) {

        if (request.idPatient() == null) {
            throw new IllegalArgumentException(
                    "El paciente es obligatorio para el agendamiento autónomo"
            );
        }

        return schedule(
                request,
                SchedulingOrigin.AUTONOMO
        );
    }

    private static Appointment schedule(
            AppointmentSchedulingRequest request,
            SchedulingOrigin origin) {

        Objects.requireNonNull(request.idDoctor());
        Objects.requireNonNull(request.doctorName());
        Objects.requireNonNull(request.specialty());
        Objects.requireNonNull(request.date());
        Objects.requireNonNull(request.startTime());

        return new Appointment(
                null,
                request.idDoctor(),
                request.doctorName(),
                request.idPatient(),
                request.patientName(),
                request.patientInfo(),
                request.specialty(),
                request.date(),
                request.startTime(),
                origin
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

    // Cambiar cuando se implemente lo de reprogramadas
    public void changeState(AppointmentState appointmentState) {
        if (this.appointmentState != AppointmentState.AGENDADA) {
            throw new IllegalStateException(
                    "Solo se puede cambiar el estado de citas agendadas"
            );
        }

        this.appointmentState = appointmentState;
    }

    public void cancel() {
        if (this.appointmentState != AppointmentState.AGENDADA) {
            throw new IllegalStateException(
                    "Solo las citas AGENDADAS pueden cancelarse. Estado actual: " + this.appointmentState
            );
        }
        this.appointmentState = AppointmentState.CANCELADA;
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

    //no se está usando, revisarlo
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
}
