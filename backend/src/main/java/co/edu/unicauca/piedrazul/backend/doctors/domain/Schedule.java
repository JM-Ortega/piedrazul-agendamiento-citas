package co.edu.unicauca.piedrazul.backend.doctors.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

// Lombok
@RequiredArgsConstructor
@Setter
@Getter

@Entity
@Table(
        name = "schedule",
        schema = "piedrazul",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_schedule_doctor_workday",
                        columnNames = {"doctor_id", "workday"}
                )
        }
)
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "workday", nullable = false, length = 20)
    private Workday workday;

    public Schedule(Doctor doctor, LocalTime startTime, LocalTime endTime, Workday workday) {
        this.doctor = doctor;
        this.startTime = startTime;
        this.endTime = endTime;
        this.workday = workday;
    }
}
