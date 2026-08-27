package co.edu.unicauca.piedrazul.backend.doctors.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Setter
@Getter

@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
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

    @ElementCollection(targetClass = Specialty.class)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
            name = "doctor_specialties",
            joinColumns = @JoinColumn(name = "id_doctor", nullable = false)
    )
    @Column(name = "specialty", nullable = false)
    private List<Specialty> specialty = new ArrayList<>();

    @Column(name = "status", nullable = false)
    private boolean status;

    @Column(name = "labor_start", nullable = false)
    private LocalDate laborStart;

    @Column(name = "labor_end")
    private LocalDate laborEnd;

    @Column(name = "appointment_interval", nullable = false)
    private int appointmentInterval;

    @OneToMany(mappedBy = "doctor", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Schedule> schedules = new ArrayList<>();

    // Al momento de registrar/crearle una cuenta al doctor se le deben llenar todos estos campos,
    // el registro de doctores deberia hacerlo solo el administrador
    public Doctor(UUID idUser, String firstName, String lastName, String identification, DocumentType documentType,
                  String phone, List<Specialty> specialty, boolean status, LocalDate laborStart,
                  LocalDate laborEnd, int appointmentInterval, List<Schedule> schedules) {
        this.idUser = idUser;
        this.firstName = firstName;
        this.lastName = lastName;
        this.identification = identification;
        this.documentType = documentType;
        this.phone = phone;
        this.specialty = specialty;
        this.status = status;
        this.laborStart = laborStart;
        this.laborEnd = laborEnd;
        this.appointmentInterval = appointmentInterval;
        this.schedules = schedules;
    }
}