package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.jackson.normalization.NormalizeName;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class RegisterUnscheduledAttentionRequest {

    @NotNull(message = "El tipo de documento es obligatorio")
    private DocumentType documentType;

    @NotBlank(message = "El número de documento es obligatorio")
    @Sanitize
    private String documentNumber;

    @NotBlank(message = "El nombre es obligatorio")
    @NormalizeName
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    @NormalizeName
    private String lastName;

    @NotBlank(message = "El teléfono es obligatorio")
    @Sanitize
    private String phone;

    private Gender gender;
    private LocalDate birthDate;

    @Sanitize
    private String email;

    @Sanitize
    private String guardianPhone;

    @NotNull(message = "La especialidad de atención es obligatoria")
    private SpecialtyCode specialty;

    @Sanitize
    private String medicalCheckup; // opcional — puede venir vacío o null
}
