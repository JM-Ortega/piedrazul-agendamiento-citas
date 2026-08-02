package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import jakarta.persistence.*;
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
@Table(name = "appointment", schema = "piedrazul")
public class AppointmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID idAppointment;

    @Column(name = "doctor_id", nullable = false)
    private UUID idDoctor;

    @Column(name = "patient_id", nullable = false)
    private UUID idPatient;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty_code", nullable = false)
    private SpecialtyCode specialty;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_code", nullable = false)
    private AppointmentState appointmentState;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "scheduling_origin", nullable = false)
    private SchedulingOrigin schedulingOrigin;

}