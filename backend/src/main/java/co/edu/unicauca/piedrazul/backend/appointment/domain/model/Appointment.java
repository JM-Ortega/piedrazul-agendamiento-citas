package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class Appointment {
    private UUID idCita;
    private UUID idDoctor;
    private UUID idPatient;
    private Specialty specialty;
    private AppointmentState appointmentState;
    private LocalDate date;
    private AppointmentTime startTime;
    private SchedulingOrigin schedulingOrigin;
    private List<AgendaChange> agendaChanges;

    public Appointment(UUID idCita, UUID idDoctor, UUID idPatient, Specialty specialty, AppointmentState appointmentState, LocalDate date, AppointmentTime startTime, SchedulingOrigin schedulingOrigin, List<AgendaChange> agendaChanges) {
        this.idCita = idCita;
        this.idDoctor = idDoctor;
        this.idPatient = idPatient;
        this.specialty = specialty;
        this.appointmentState = appointmentState;
        this.date = date;
        this.startTime = startTime;
        this.schedulingOrigin = schedulingOrigin;
        this.agendaChanges = agendaChanges;
    }
}
