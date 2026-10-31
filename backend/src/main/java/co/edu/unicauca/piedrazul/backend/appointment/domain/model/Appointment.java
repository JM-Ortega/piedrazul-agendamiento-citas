package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;


public class Appointment {
    private final UUID idAppointment;
    private final UUID idDoctor;
    private final UUID idPatient;
    private final SpecialtyCode specialty;
    private AppointmentState appointmentState;
    private final LocalDate date;
    private final AppointmentTime startTime;
    private final SchedulingOrigin schedulingOrigin;

    // Constructor privado — solo accesible desde los factory methods
    private Appointment(UUID idAppointment,
                        UUID idDoctor,
                        UUID idPatient,
                        SpecialtyCode specialty,
                        LocalDate date,
                        AppointmentTime startTime,
                        SchedulingOrigin schedulingOrigin) {
        this.idAppointment = idAppointment;
        this.idDoctor = idDoctor;
        this.idPatient = idPatient;
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

    //Factory Method 3
    public static Appointment registerUnscheduledAttention(UUID idDoctor, UUID idPatient, SpecialtyCode specialty) {
        Objects.requireNonNull(idDoctor);
        Objects.requireNonNull(idPatient);
        Objects.requireNonNull(specialty);

        Appointment appointment = new Appointment(
                null,
                idDoctor,
                idPatient,
                specialty,
                LocalDate.now(),
                AppointmentTime.withoutBusinessHoursRestriction(LocalTime.now()),
                SchedulingOrigin.SIN_CITA);

        appointment.appointmentState = AppointmentState.ATENDIDA;

        return appointment;
    }

    private static Appointment schedule(
            AppointmentSchedulingRequest request,
            SchedulingOrigin origin) {

        Objects.requireNonNull(request.idDoctor());
        Objects.requireNonNull(request.specialty());
        Objects.requireNonNull(request.date());
        Objects.requireNonNull(request.startTime());

        return new Appointment(
                null,
                request.idDoctor(),
                request.idPatient(),
                request.specialty(),
                request.date(),
                request.startTime(),
                origin
        );
    }

    public static Appointment reconstruct (UUID idAppointment,
                                          UUID idDoctor,
                                          UUID idPatient,
                                          SpecialtyCode specialty,
                                          AppointmentState appointmentState,
                                          LocalDate date,
                                          AppointmentTime startTime,
                                          SchedulingOrigin schedulingOrigin) {

        // Usa el constructor privado igual que los otros factory methods
        Appointment appointment = new Appointment(
                idAppointment,
                idDoctor,
                idPatient,
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

    public SpecialtyCode getSpecialty() {
        return specialty;
    }

    public UUID getIdPatient() {
        return idPatient;
    }

    public UUID getIdDoctor() {
        return idDoctor;
    }
}
