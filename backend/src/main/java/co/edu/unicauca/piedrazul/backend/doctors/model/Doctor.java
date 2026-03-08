package co.edu.unicauca.piedrazul.backend.doctors.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "doctors")
public class Doctor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_doctor")
    private long idDoctor;

    @Column(name = "id_user", nullable = false, unique = true)
    private long idUser;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false)
    private Specialty specialty;

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

}
