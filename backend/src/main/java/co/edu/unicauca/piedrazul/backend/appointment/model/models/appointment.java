package co.edu.unicauca.piedrazul.backend.appointment.model.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.FutureOrPresent;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "appointments")
public class appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "appointment_id")
    private long idAppointment;

    /* Si utilizamos el tipo de dato UUID no podemos utilizar
    @JoinColumn ya que esto es solo para manejar entidades*/

    @Column(name = "doctor_id",  nullable = false)
    private UUID idDoctor;

    @Column(name = "patient_id", nullable = false)
    private UUID idPatient;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false)
    private enumSpecialty specialty;

    @Enumerated(EnumType.STRING)
    @Column(name = "appot_state", nullable = false)
    private enumAppointmentState appointmentState;

    @FutureOrPresent
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "startTime", nullable = false)
    private LocalTime startTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "scheduling_origin")
    private enumSchedulingOrigin schedulingOrigin;

    public appointment(UUID idDoctor, UUID idPatient, enumSpecialty specialty, enumAppointmentState appointmentState, LocalDate date, LocalTime startTime, enumSchedulingOrigin schedulingOrigin) {
        this.idDoctor = idDoctor;
        this.idPatient = idPatient;
        this.specialty = specialty;
        this.appointmentState = appointmentState;
        this.date = date;
        this.startTime = startTime;
        this.schedulingOrigin = schedulingOrigin;
    }

    public appointment() {
    }
}
