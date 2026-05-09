package co.edu.unicauca.piedrazul.backend.user.api.dto;

import co.edu.unicauca.piedrazul.backend.config.security.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateUserRequest {

    @NotBlank(message = "username is required")
    @Size(min = 4, max = 50, message = "username must be between 4 and 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$", message = "username contains invalid characters")
    @Sanitize
    private String username;

    @NotNull(message = "role is required")
    private Role role;

    public CreateUserRequest() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }
}