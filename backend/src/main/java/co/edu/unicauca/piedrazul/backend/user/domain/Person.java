package co.edu.unicauca.piedrazul.backend.user.domain;

import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "person",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_person_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uq_person_identification", columnNames = "identification")
        }
)
@ValidDocument(
        documentField = "identification",
        typeField = "identificationType"
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
}