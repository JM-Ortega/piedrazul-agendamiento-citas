package co.edu.unicauca.piedrazul.backend.doctors.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.*;

@RequiredArgsConstructor
@Setter
@Getter

@Entity
@Table(name = "doctor", schema = "piedrazul")
public class Doctor {

    @Id
    @Column(name = "peson_id", updatable = false, nullable = false)
    private UUID personId;

    // BORRAR I
    //@Id
    /*
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_doctor", updatable = false, nullable = false)
    private UUID idDoctor;

    @Column(name = "id_user", unique = true)
    private UUID idUser;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "identification", nullable = false, length = 100)
    private String identification;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private DocumentType documentType;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @ElementCollection(targetClass = SpecialtyCode.class)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
            name = "doctor_specialties",
            joinColumns = @JoinColumn(name = "id_doctor", nullable = false)
    )
    @Column(name = "specialty", nullable = false)
    private List<SpecialtyCode> specialty = new ArrayList<>();
     */
    // BORRAR F

    @Column(name = "labor_start", nullable = false)
    private LocalDate laborStart;

    @Column(name = "labor_end")
    private LocalDate laborEnd;

    @Column(name = "booking_window_weeks", nullable = false)
    private int bookingWindowWeeks;

    @Column(name = "status", nullable = false)
    private boolean status;

    @Column(name = "appointment_interval", nullable = false)
    private int appointmentInterval;

    @OneToMany(mappedBy = "doctor", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Schedule> schedules = new HashSet<>();

    @ManyToMany
    @JoinTable(
            name = "doctor_specialty",
            schema = "piedrazul",
            joinColumns = @JoinColumn(name = "doctor_id"),
            inverseJoinColumns = @JoinColumn(name = "specialty_code")
    )
    private Set<Specialty> specialties = new HashSet<>();


    public boolean tieneEspecialidad(SpecialtyCode code) {
        return specialties.stream().anyMatch(s -> s.getCode().equals(code));
    }

    // Al momento de registrar/crearle una cuenta al doctor se le deben llenar todos estos campos,
    // el registro de doctores deberia hacerlo solo el administrador
    public Doctor(UUID personId, LocalDate laborStart, LocalDate laborEnd,
                  int bookingWindowWeeks, Boolean status, Integer appointmentInterval) {
        this.personId = personId;
        this.laborStart = laborStart;
        this.laborEnd = laborEnd;
        this.bookingWindowWeeks = bookingWindowWeeks;
        this.status = status;
        this.appointmentInterval = appointmentInterval;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Doctor other)) return false;
        return personId != null && personId.equals(other.personId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(personId);
    }
}