package co.edu.unicauca.piedrazul.backend.user.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(
        name = "person",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_person_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uq_person_identification", columnNames = "identification")
        }
)
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", unique = true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "identification_type", nullable = false, length = 40)
    private IdentificationType identificationType;

    @Column(name = "identification", nullable = false, length = 100)
    private String identification;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    protected Person() {
        // Required by JPA
    }

    public Person(
            UUID userId,
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email
    ) {
        this.userId = userId;
        this.identificationType = identificationType;
        this.identification = identification;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.email = email;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public IdentificationType getIdentificationType() {
        return identificationType;
    }

    public void setIdentificationType(IdentificationType identificationType) {
        this.identificationType = identificationType;
    }

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}