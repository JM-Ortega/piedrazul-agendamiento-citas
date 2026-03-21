package co.edu.unicauca.piedrazul.backend.patients.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patients")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_patient", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType;

    @Column(name = "document_number", nullable = false, unique = true, length = 20)
    private String documentNumber;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 20)
    private Gender gender;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "guardian_phone")
    private String guardianPhone;

    protected Patient() {
    }

    public Patient(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            Gender gender,
            LocalDate birthDate,
            String guardianPhone,
            UUID userId
    ) {
        if (documentType == null)
            throw new IllegalArgumentException("documentType is required");

        if (documentNumber == null || documentNumber.isBlank())
            throw new IllegalArgumentException("documentNumber is required");

        if (firstName == null || firstName.isBlank())
            throw new IllegalArgumentException("firstName is required");

        if (lastName == null || lastName.isBlank())
            throw new IllegalArgumentException("lastName is required");

        if (phone == null || phone.isBlank())
            throw new IllegalArgumentException("phone is required");

        if (gender == null)
            throw new IllegalArgumentException("gender is required");

        if (birthDate == null)
            throw new IllegalArgumentException("birthDate is required");

        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.email = email;
        this.gender = gender;
        this.birthDate = birthDate;
        this.guardianPhone = guardianPhone;
        this.userId = userId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public Gender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }

    public boolean hasUserAccount() {
        return userId != null;
    }

    public void linkUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }

        if (this.userId != null) {
            throw new IllegalStateException("patient already has a user linked");
        }

        this.userId = userId;
    }
}