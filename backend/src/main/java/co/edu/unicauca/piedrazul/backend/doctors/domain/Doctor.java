package co.edu.unicauca.piedrazul.backend.doctors.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//Lombok
@RequiredArgsConstructor
@Setter
@Getter

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

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false)
    private List<Specialty> specialty = new ArrayList<>();

    @Column(name = "status", nullable = false)
    private boolean status;

    @Column(name = "labor_start")
    private LocalDate  laborStart;

    @Column(name = "labor_end")
    private LocalDate laborEnd;

    @Column(name = "appointment_interval", nullable = false)
    private int appointmentInterval;

    @Column(name = "schedulable_weeks", nullable = false)
    private int schedulableWeeks;

    @OneToMany(mappedBy = "doctor", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Schedule> schedules = new ArrayList<>();

    //Al momento de registrar/crearle una cuenta al doctor se le deben llenar todos estos campos,
    // el registro de doctores deberia hacerlo solo el administrador
    public Doctor(UUID idUser, String firstName, String lastName, List<Specialty> specialty, boolean status, LocalDate laborStart,
                  LocalDate laborEnd, int appointmentInterval, int schedulableWeeks, List<Schedule> schedules) {
        this.idUser = idUser;
        this.firstName = firstName;
        this.lastName = lastName;
        this.specialty = specialty;
        this.status = status;
        this.laborStart = laborStart;
        this.laborEnd = laborEnd;
        this.appointmentInterval = appointmentInterval;
        this.schedulableWeeks = schedulableWeeks;
        this.schedules = schedules;
    }
}
