package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.UpdatePatientCommand;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatientChangesTest {

    private static final LocalDate BIRTH = LocalDate.of(1990, 5, 10);

    private final PatientData current = new PatientData(
            UUID.randomUUID(), UUID.randomUUID(), IdentificationType.CEDULA, "1002003004",
            "Ana", "Ruiz", "3001234567", "ana@example.com", PatientSex.FEMENINO, BIRTH, null);

    private UpdatePatientCommand sameAsCurrent() {
        return new UpdatePatientCommand(
                IdentificationType.CEDULA, "1002003004", "Ana", "Ruiz", "3001234567",
                "ana@example.com", PatientSex.FEMENINO, BIRTH, null);
    }

    @Test
    void identicalStateHasNoChanges() {
        PatientChanges changes = PatientChanges.between(current, sameAsCurrent());

        assertThat(changes.isEmpty()).isTrue();
        assertThat(changes.identificationChanged()).isFalse();
        assertThat(changes.accountDataChanged()).isFalse();
    }

    @Test
    void nullAndEmptyOptionalDataAreTheSame() {
        UpdatePatientCommand next = new UpdatePatientCommand(
                IdentificationType.CEDULA, "1002003004", "Ana", "Ruiz", "3001234567",
                "ana@example.com", PatientSex.FEMENINO, BIRTH, "");

        assertThat(PatientChanges.between(current, next).isEmpty()).isTrue();
    }

    @Test
    void documentChangeAffectsTheAccountAndIsMaskedInTheAudit() {
        UpdatePatientCommand next = new UpdatePatientCommand(
                IdentificationType.CEDULA_EXTRANJERIA, "9998887776", "Ana", "Ruiz", "3001234567",
                "ana@example.com", PatientSex.FEMENINO, BIRTH, null);

        PatientChanges changes = PatientChanges.between(current, next);

        assertThat(changes.identificationChanged()).isTrue();
        assertThat(changes.accountDataChanged()).isTrue();
        assertThat(changes.beforeJson())
                .contains("\"identificationType\":\"CEDULA\"")
                .contains("\"identification\":\"******3004\"")
                .doesNotContain("1002003004");
        assertThat(changes.afterJson())
                .contains("\"identificationType\":\"CEDULA_EXTRANJERIA\"")
                .contains("\"identification\":\"******7776\"")
                .doesNotContain("9998887776");
    }

    @Test
    void documentTypeAloneDoesNotTouchTheAccount() {
        UpdatePatientCommand next = new UpdatePatientCommand(
                IdentificationType.CEDULA_EXTRANJERIA, "1002003004", "Ana", "Ruiz", "3001234567",
                "ana@example.com", PatientSex.FEMENINO, BIRTH, null);

        PatientChanges changes = PatientChanges.between(current, next);

        assertThat(changes.isEmpty()).isFalse();
        assertThat(changes.identificationChanged()).isFalse();
        assertThat(changes.accountDataChanged()).isFalse();
    }

    @Test
    void phoneAndGuardianPhoneAreNotStoredInTheAccount() {
        UpdatePatientCommand next = new UpdatePatientCommand(
                IdentificationType.CEDULA, "1002003004", "Ana", "Ruiz", "3119998877",
                "ana@example.com", PatientSex.FEMENINO, BIRTH, "3001112233");

        PatientChanges changes = PatientChanges.between(current, next);

        assertThat(changes.isEmpty()).isFalse();
        assertThat(changes.accountDataChanged()).isFalse();
    }

    @Test
    void nameAndEmailChangesAffectTheAccount() {
        UpdatePatientCommand names = new UpdatePatientCommand(
                IdentificationType.CEDULA, "1002003004", "Anabel", "Ruiz", "3001234567",
                "ana@example.com", PatientSex.FEMENINO, BIRTH, null);
        UpdatePatientCommand email = new UpdatePatientCommand(
                IdentificationType.CEDULA, "1002003004", "Ana", "Ruiz", "3001234567",
                "nuevo@example.com", PatientSex.FEMENINO, BIRTH, null);

        assertThat(PatientChanges.between(current, names).accountDataChanged()).isTrue();
        assertThat(PatientChanges.between(current, email).accountDataChanged()).isTrue();
    }

    @Test
    void auditOnlyNamesTheChangedFieldsAndNeverStoresPersonalData() {
        UpdatePatientCommand next = new UpdatePatientCommand(
                IdentificationType.CEDULA, "1002003004", "Anabel", "Ruiz", "3119998877",
                "nuevo@example.com", PatientSex.OTRO, LocalDate.of(1991, 1, 1), "3001112233");

        PatientChanges changes = PatientChanges.between(current, next);
        String before = changes.beforeJson();
        String after = changes.afterJson();

        assertThat(before).contains("firstName", "phone", "email", "sex", "birthDate", "guardianPhone")
                .doesNotContain("lastName", "identification");
        assertThat(after).contains("\"sex\":\"OTRO\"");
        assertThat(before + after).doesNotContain(
                "Anabel", "Ana\"", "3119998877", "3001234567", "nuevo@example.com",
                "ana@example.com", "1991", "1990", "3001112233");
    }
}
