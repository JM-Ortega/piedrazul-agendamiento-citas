package co.edu.unicauca.piedrazul.backend.user.api.dto;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateUserRequest {

    @NotBlank(message = "username is required")
    @Size(max = 50, message = "username must not exceed 50 characters")
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