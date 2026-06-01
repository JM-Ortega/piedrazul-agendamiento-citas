package co.edu.unicauca.piedrazul.backend.user.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.normalization.NormalizeName;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import jakarta.validation.constraints.*;


public record CreateSystemUserRequest(
        @NotBlank
        @Size(min = 4, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$")
        @Sanitize
        String identification,

        @NotBlank
        @Size(min = 2, max = 60)
        @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
        @Sanitize
        @NormalizeName
        String firstName,

        @NotBlank
        @Size(min = 2, max = 60)
        @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
        @Sanitize
        @NormalizeName
        String lastName,

        @Email
        @Size(max = 120)
        @Sanitize
        String email,

        @NotBlank
        @Size(min = 6, max = 100)
        String password
) {
}