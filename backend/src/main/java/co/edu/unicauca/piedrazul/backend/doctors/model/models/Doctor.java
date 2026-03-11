package co.edu.unicauca.piedrazul.backend.doctors.model.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "doctors")
public class Doctor {
    @Getter
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id_doctor", updatable = false, nullable = false)
    private UUID idDoctor;

    @Column(name = "id_user", nullable = false, unique = true)
    private UUID idUser;

    @Setter
    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false)
    private Specialty specialty;

    @Setter
    @Column(name = "status", nullable = false)
    private boolean status;

    @Column(name = "labor_start")
    private LocalDate  laborStart;

    @Column(name = "labor_end")
    private LocalDate laborEnd;

    @Column(name = "appointment_interval", nullable = false)
    private int appointmentInterval;

    @Column(name = "scheduleable_weeks", nullable = false)
    private int scheduleableWeeks;

    @OneToMany(mappedBy = "idDoctor", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Schedule> schedules = new ArrayList<>();

    public Doctor(UUID idUser, String firstName, String lastName, Specialty specialty, boolean status, LocalDate laborStart,
                  LocalDate laborEnd, int appointmentInterval, int scheduleableWeeks, List<Schedule> schedules) {
        this.idUser = idUser;
        this.firstName = firstName;
        this.lastName = lastName;
        this.specialty = specialty;
        this.status = status;
        this.laborStart = laborStart;
        this.laborEnd = laborEnd;
        this.appointmentInterval = appointmentInterval;
        this.scheduleableWeeks = scheduleableWeeks;
        this.schedules = schedules;
    }

    public Doctor() {
    }
}
