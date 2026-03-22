package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.FutureOrPresent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "appointment")
public class AppointmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_appointment", updatable = false, nullable = false)
    private UUID idAppointment;

    @Column(name = "id_doctor",nullable = false)
    private UUID idDoctor;

    @Column(name =  "doctor_name", nullable = false)
    private String doctorName;

    @Column(name = "id_patient", nullable = false)
    private UUID idPatient;

    @Column(name = "patient_name", nullable = false)
    private String patientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false)
    private Specialty specialty;

    @Enumerated(EnumType.STRING)
    @Column(name = "appot_state", nullable = false)
    private AppointmentState appointmentState;

    @FutureOrPresent
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "scheduling_origin", nullable = false)
    private SchedulingOrigin schedulingOrigin;
}
