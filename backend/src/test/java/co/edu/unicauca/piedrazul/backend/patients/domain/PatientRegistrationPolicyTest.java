package co.edu.unicauca.piedrazul.backend.patients.domain;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatientRegistrationPolicyTest {

    private static final LocalDate ADULT = LocalDate.now().minusYears(30);
    private static final LocalDate MINOR = LocalDate.now().minusYears(10);

    @Test
    void shouldRejectFutureBirthDate() {
        assertThrows(InvalidPatientDataException.class, () ->
                PatientRegistrationPolicy.validate(
                        IdentificationType.CEDULA, LocalDate.now().plusDays(1), null));
    }

    @Test
    void shouldRequireGuardianPhoneForMinors() {
        assertThrows(InvalidPatientDataException.class, () ->
                PatientRegistrationPolicy.validate(
                        IdentificationType.TARJETA_IDENTIDAD, MINOR, null));
    }

    @Test
    void shouldAcceptMinorWithGuardianPhone() {
        assertDoesNotThrow(() ->
                PatientRegistrationPolicy.validate(
                        IdentificationType.TARJETA_IDENTIDAD, MINOR, "3001234567"));
    }

    @Test
    void shouldRejectMinorWithCedula() {
        assertThrows(InvalidPatientDataException.class, () ->
                PatientRegistrationPolicy.validate(
                        IdentificationType.CEDULA, MINOR, "3001234567"));
    }

    @Test
    void shouldRejectAdultWithMinorDocumentType() {
        assertThrows(InvalidPatientDataException.class, () ->
                PatientRegistrationPolicy.validate(
                        IdentificationType.TARJETA_IDENTIDAD, ADULT, null));
    }

    @Test
    void shouldAcceptAdultWithCedulaAndNoGuardianPhone() {
        assertDoesNotThrow(() ->
                PatientRegistrationPolicy.validate(IdentificationType.CEDULA, ADULT, null));
    }

    @Test
    void shouldNotFailWhenBirthDateIsMissing() {
        // La obligatoriedad la impone el agregado Patient; la política no debe
        // adelantarse con un NullPointerException.
        assertDoesNotThrow(() ->
                PatientRegistrationPolicy.validate(IdentificationType.CEDULA, null, null));
    }

    @Test
    void shouldSkipDocumentCoherenceWhenTypeIsMissing() {
        assertDoesNotThrow(() -> PatientRegistrationPolicy.validate(null, ADULT, null));
    }
}
