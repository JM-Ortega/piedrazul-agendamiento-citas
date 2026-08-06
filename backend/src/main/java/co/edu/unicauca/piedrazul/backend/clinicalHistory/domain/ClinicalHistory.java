package co.edu.unicauca.piedrazul.backend.clinicalHistory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Entity
@RequiredArgsConstructor
@Table(name = "clinical_history")
public class ClinicalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID idPatient;

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID idAppointment;

    @Column(name = "date_attention", nullable = false, updatable = false)
    private LocalDate attendedAt;

    @Column(name = "doctor_name", nullable = false, updatable = false, length = 200)
    private String doctor_name;

    @Column(name = "description", nullable = false, updatable = false, length = 500)
    private String description;

    public ClinicalHistory(UUID idPatient, UUID idAppointment, LocalDate attendedAt, String doctor_name, String description) {
        this.idPatient = idPatient;
        this.idAppointment = idAppointment;
        this.attendedAt = attendedAt;
        this.doctor_name = doctor_name;
        this.description = description;
    }
}
