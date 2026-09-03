package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
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
    private String documentNumber;

    @NotBlank(message = "El nombre es obligatorio")
    private String firstName;

    @NotBlank(message = "El apellido es obligatorio")
    private String lastName;

    @NotBlank(message = "El teléfono es obligatorio")
    private String phone;

    private Gender gender;
    private LocalDate birthDate;
    private String email;
    private String guardianPhone;

    @NotNull(message = "La especialidad de atención es obligatoria")
    private SpecialtyCode specialty;

    private String medicalCheckup; // opcional — puede venir vacío o null
}
