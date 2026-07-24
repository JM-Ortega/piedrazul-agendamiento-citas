package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@ValidDocument(
        documentField = "documentNumber",
        typeField = "documentType"
)
public class AppointmentRequest {
    @NotNull(message = "El médico es obligatorio")
    private UUID doctorId;

    @NotNull(message = "La especialidad es obligatoria")
    private Specialty specialty;

    @NotNull(message = "La fecha es obligatoria")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    @NotNull(message = "El origen es obligatorio")
    private SchedulingOrigin schedulingOrigin;

    // Solo obligatorio en agendamiento autónomo
    private UUID patientId;

    // Solo obligatorios en agendamiento manual
    private DocumentType documentType;

    @Size(max = 20)
    @Sanitize
    private String documentNumber;

    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    private String firstName;

    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    private String lastName;

    @Pattern(regexp = "^[0-9]{7,15}$")
    @Sanitize
    private String phone;

    private Gender gender;

    private LocalDate birthDate; // opcional

    @Email
    @Size(max = 120)
    @Sanitize
    private String email;        // opcional

    @Pattern(regexp = "^[0-9]{7,15}$")
    @Sanitize
    private String guardianPhone; // opcional, para pacientes menores de edad

    // Se valida dependiendo del tipo de agendamiento
    public void validate() {
        if (schedulingOrigin == SchedulingOrigin.AUTONOMO
                && patientId == null) {
            throw new IllegalArgumentException(
                    "El id del paciente es obligatorio para agendamiento autónomo"
            );
        }
        if (schedulingOrigin == SchedulingOrigin.MANUAL
                && (isBlank(documentNumber) || isBlank(firstName) || isBlank(phone))) {
            throw new IllegalArgumentException(
                    "El numero de documento, nombre completo y teléfono son obligatorios para agendamiento manual"
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
