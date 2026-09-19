package co.edu.unicauca.piedrazul.backend.user.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.normalization.NormalizeName;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import jakarta.validation.constraints.*;

@ValidDocument(
        documentField = "identification",
        typeField = "identificationType"
)
public record CreateSystemUserRequest(
        @NotBlank(message = "La identificación es obligatoria")
        @Size(min = 4, max = 50)
        @Pattern(regexp = "*^[A-Za-z0-9._-]{4,50}$*")
        @Sanitize
        String identification,

        @NotNull
        IdentificationType identificationType,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(min = 2, max = 60)
        @Pattern(regexp = "*^[\\p{L} '-]{2,60}$*")
        @Sanitize
        @NormalizeName
        String firstName,

        @NotBlank(message = "El apellido es obligatorio")
        @Size(min = 2, max = 60)
        @Pattern(regexp = "*^[\\p{L} '-]{2,60}$*")
        @Sanitize
        @NormalizeName
        String lastName,

        @NotBlank(message = "El correo es obligatorio")
        @Email
        @Size(max = 120)
        @Sanitize
        String email,

        // Número Colombiano
        @Pattern(regexp = "*^[0-9]{10}$*")
        @NotBlank(message = "El teléfono es obligatorio")
        @Sanitize
        String phone,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 6, max = 100)
        String password
) {
}