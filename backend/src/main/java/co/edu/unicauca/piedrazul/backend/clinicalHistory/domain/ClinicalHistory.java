package co.edu.unicauca.piedrazul.backend.clinicalHistory.domain;

import jakarta.persistence.*;
import lombok.Getter;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Entity
@Table(name = "clinical_history")
public class ClinicalHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_clinical_history", nullable = false, updatable = false)
    private UUID idClinicalHistory;

    @Column(name = "id_appointment", nullable = false, updatable = false)
    private UUID idAppointment;

    @Column(name = "id_doctor", nullable = false, updatable = false)
    private UUID idDoctor;

    @Column(name = "id_patient", nullable = false, updatable = false)
    private UUID idPatient;

    @Column(name = "attended_at", nullable = false, updatable = false)
    private LocalDate attendedAt;

    @Column(name = "description", nullable = false, updatable = false, length = 500)
    private String description;

    protected ClinicalHistory() {}

    public ClinicalHistory(UUID idAppointment, UUID idDoctor, UUID idPatient,
                           LocalDate attendedAt, String description) {
        this.idAppointment = idAppointment;
        this.idDoctor = idDoctor;
        this.idPatient = idPatient;
        this.attendedAt = attendedAt;
        this.description = description;
    }
}
