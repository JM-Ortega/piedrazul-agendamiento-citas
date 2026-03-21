package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.*;
import org.jmolecules.ddd.annotation.ValueObject;

import java.time.LocalDate;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

@ValueObject
public class PatientInfo {

    private final DocumentType documentType;
    private final String documentNumber;
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final Gender gender;
    private final LocalDate birthDate;
    private final String email;
    private final String guardianPhone;

    private PatientInfo(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone
    ) {
        this.documentType = validateRequired(documentType, "El tipo de documento es obligatorio");
        this.documentNumber = normalizeRequired(documentNumber, "El número de documento es obligatorio");
        this.firstName = normalizeRequired(firstName, "El nombre es obligatorio");
        this.lastName = normalizeRequired(lastName, "El apellido es obligatorio");
        this.phone = normalizeRequired(phone, "El celular es obligatorio");
        this.gender = validateRequired(gender, "El género es obligatorio");
        this.birthDate = birthDate;
        this.email = normalizeOptional(email);
        this.guardianPhone = normalizeOptional(guardianPhone);

        validate();
    }

    public static PatientInfo of(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone
    ) {
        return new PatientInfo(
                documentType,
                documentNumber,
                firstName,
                lastName,
                phone,
                gender,
                birthDate,
                email,
                guardianPhone
        );
    }

    private void validate() {

        if (!documentNumber.matches("\\d{6,12}")) {
            throw new InvalidDocumentException("El número de documento debe tener entre 6 y 12 dígitos");
        }

        if (!firstName.matches("^[A-Za-zÁÉÍÓÚáéíóúÑñ ]+$")) {
            throw new InvalidPersonNameException("El nombre contiene caracteres inválidos");
        }

        if (!lastName.matches("^[A-Za-zÁÉÍÓÚáéíóúÑñ ]+$")) {
            throw new InvalidPersonNameException("El apellido contiene caracteres inválidos");
        }

        if (!phone.matches("\\d{7,15}")) {
            throw new InvalidPhoneException("El celular debe contener entre 7 y 15 dígitos");
        }

        if (birthDate != null && birthDate.isAfter(LocalDate.now())) {
            throw new InvalidBirthDateException("La fecha de nacimiento no puede ser futura");
        }

        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new InvalidEmailException("El correo no tiene un formato válido");
        }

        if (birthDate != null) {
            int age = java.time.Period.between(birthDate, LocalDate.now()).getYears();

            if (age < 18 && (guardianPhone == null || guardianPhone.isBlank())) {
                throw new GuardianRequiredException("Para menores de edad se requiere teléfono del acudiente");
            }

            if (age < 18 && documentType == DocumentType.CEDULA) {
                throw new InconsistentPatientInfoException("Un menor no puede tener cédula");
            }

            if (age >= 18 && (documentType == DocumentType.TARJETA_IDENTIDAD
                    || documentType == DocumentType.REGISTRO_NACIMIENTO)) {
                throw new InconsistentPatientInfoException("Un adulto no debería tener este tipo de documento");
            }
        }
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new InvalidPatientInfoException(message);
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static <T> T validateRequired(T value, String message) {
        if (value == null) {
            throw new InvalidPatientInfoException(message);
        }
        return value;
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

    public Gender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getEmail() {
        return email;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatientInfo)) return false;
        PatientInfo that = (PatientInfo) o;
        return Objects.equals(documentType, that.documentType) &&
                Objects.equals(documentNumber, that.documentNumber) &&
                Objects.equals(firstName, that.firstName) &&
                Objects.equals(lastName, that.lastName) &&
                Objects.equals(phone, that.phone) &&
                Objects.equals(gender, that.gender) &&
                Objects.equals(birthDate, that.birthDate) &&
                Objects.equals(email, that.email) &&
                Objects.equals(guardianPhone, that.guardianPhone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                documentType,
                documentNumber,
                firstName,
                lastName,
                phone,
                gender,
                birthDate,
                email,
                guardianPhone
        );
    }


}
