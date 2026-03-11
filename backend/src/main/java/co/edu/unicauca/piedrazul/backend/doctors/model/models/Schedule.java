package co.edu.unicauca.piedrazul.backend.doctors.model.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

@Setter
@Getter
@Entity
@Table(name = "schedules")
public class Schedule {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id_schedule")
    private UUID idSchedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_doctor", nullable = false)
    private Doctor doctor;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "workday", nullable = false)
    private Workday workday;

    public Schedule(Doctor doctor, LocalTime startTime, LocalTime endTime, Workday workday) {
        this.doctor = doctor;
        this.startTime = startTime;
        this.endTime = endTime;
        this.workday = workday;
    }

    public Schedule() {

    }
}
