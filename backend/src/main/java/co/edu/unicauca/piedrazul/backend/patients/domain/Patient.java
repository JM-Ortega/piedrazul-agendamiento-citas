package co.edu.unicauca.piedrazul.backend.patients.domain;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sex", nullable = false, length = 20)
    private Sex sex;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(name = "guardian_phone", length = 20)
    private String guardianPhone;

    protected Patient() {
    }

    public Patient(
            UUID personId,
            Sex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        if (personId == null)
            throw new InvalidPatientDataException("personId is required");

        if (sex == null)
            throw new InvalidPatientDataException("sex is required");

        if (birthDate == null)
            throw new InvalidPatientDataException("birthDate is required");

        this.personId = personId;
        this.sex = sex;
        this.birthDate = birthDate;
        this.guardianPhone = guardianPhone;
    }

    public UUID getPersonId() {
        return personId;
    }

    public Sex getSex() {
        return sex;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }
}
