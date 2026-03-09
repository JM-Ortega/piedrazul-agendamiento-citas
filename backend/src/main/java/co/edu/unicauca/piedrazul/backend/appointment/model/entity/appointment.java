package co.edu.unicauca.piedrazul.backend.appointment.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "appointments")
public class appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "appointment_id")
    private long idAppot;

    @ManyToOne(fetch = FetchType.LAZY)
    @Column(name = "doctor_id",  nullable = false)
    private Doctor idDoctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @Column(name = "patient_id", nullable = false)
    private Patient idPatient;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false)
    private enumSpecialty specialty;

    @Enumerated(EnumType.STRING)
    @Column(name = "appot_state", nullable = false)
    private enumAppointmentState appointmentState;

    @Column(name = "date_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "scheduling_origin")
    private enumSchedulingOrigin schedulingOrigin;


}
