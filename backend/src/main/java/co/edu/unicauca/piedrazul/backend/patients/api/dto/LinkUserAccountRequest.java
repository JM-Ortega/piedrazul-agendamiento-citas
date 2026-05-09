package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.config.security.sanitization.Sanitize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class LinkUserAccountRequest {

    @NotBlank
    @Size(max = 12)
    @Pattern(regexp = "^[A-Za-z0-9._-]{5,30}$")
    @Sanitize
    private String documentNumber;

    @NotBlank
    @Size(min = 4, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$")
    @Sanitize
    private String username;

    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    private String firstName;

    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    private String lastName;

    @Email
    @Size(max = 120)
    @Sanitize
    private String email;

    @NotBlank
    @Size(min = 8, max = 100)
    private String password;

    public LinkUserAccountRequest() {}

    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}