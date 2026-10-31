package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientAccountSummaryProjection;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las consultas de pacientes con cuenta son SQL nativo (unaccent, ESCAPE, filtro por
 * {@code user_id}), así que solo una base real puede comprobar que devuelven lo que se
 * espera. Cada test corre en su propia transacción, que se revierte.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersonExternalServiceImp.class)
class PatientAccountedSearchIT extends PostgresIntegrationSupport {

    private static final String MARK = "Wprk";
    private static final LocalDate ADULT = LocalDate.now().minusYears(30);

    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private PersonExternalServiceImp personExternalService;

    @Autowired
    private PatientRepository patientRepository;

    private UUID withAccountId;
    private UUID otherWithAccountId;
    private UUID withoutAccountId;
    private UUID withAccountUserId;
    private String withAccountDocument;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime()).substring(0, 8);
        withAccountDocument = "1" + unique + "1";
        withAccountUserId = UUID.randomUUID();

        withAccountId = patient("Ana", MARK + " Ruiz", withAccountDocument, withAccountUserId);
        otherWithAccountId = patient("Beatriz", MARK + " Ríos", "2" + unique + "2", UUID.randomUUID());
        // Un paciente sin cuenta: no debe aparecer en ningún resultado de este listado.
        withoutAccountId = patient("Carlos", MARK + " Díaz", "3" + unique + "3", null);
        patientRepository.flush();
    }

    private UUID patient(String first, String last, String document, UUID userId) {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, document, first, last, "3001234567", first.toLowerCase() + "@example.com", userId);
        patientRepository.save(new Patient(person.id(), Sex.FEMENINO, ADULT, null));
        return person.id();
    }

    private List<UUID> ids(Page<PatientAccountSummaryProjection> page) {
        return page.getContent().stream().map(PatientAccountSummaryProjection::getId).toList();
    }

    @Test
    void withoutSearchOnlyPatientsWithAnAccountAreListed() {
        Page<PatientAccountSummaryProjection> page = patientRepository.findAccountedSummaries(PageRequest.of(0, 50));

        assertThat(ids(page)).contains(withAccountId, otherWithAccountId).doesNotContain(withoutAccountId);
    }

    @Test
    void theProjectionCarriesTheAccountId() {
        Page<PatientAccountSummaryProjection> page = patientRepository.findAccountedSummaries(PageRequest.of(0, 50));

        PatientAccountSummaryProjection row = page.getContent().stream()
                .filter(r -> r.getId().equals(withAccountId)).findFirst().orElseThrow();
        assertThat(row.getUserId()).isEqualTo(withAccountUserId);
        assertThat(row.getIdentification()).isEqualTo(withAccountDocument);
        assertThat(row.getFirstName()).isEqualTo("Ana");
        assertThat(row.getLastName()).isEqualTo(MARK + " Ruiz");
    }

    @Test
    void resultsAreOrderedByNameIgnoringAccents() {
        List<UUID> all = ids(patientRepository.findAccountedSummaries(PageRequest.of(0, 50)));

        assertThat(all.indexOf(withAccountId)).isLessThan(all.indexOf(otherWithAccountId));
    }

    @Test
    void searchIgnoresCaseAndAccentsAndExcludesPatientsWithoutAnAccount() {
        Page<PatientAccountSummaryProjection> page = patientRepository.searchAccountedSummaries(MARK, PageRequest.of(0, 50));

        assertThat(ids(page)).containsExactly(withAccountId, otherWithAccountId);
    }

    @Test
    void searchMatchesTheDocumentNumber() {
        Page<PatientAccountSummaryProjection> page = patientRepository.searchAccountedSummaries(
                withAccountDocument, PageRequest.of(0, 50));

        assertThat(ids(page)).containsExactly(withAccountId);
    }

    @Test
    void searchingByThePatientWithoutAccountFindsNothing() {
        Page<PatientAccountSummaryProjection> page = patientRepository.searchAccountedSummaries("Carlos", PageRequest.of(0, 50));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void escapedWildcardsAreLiteral() {
        assertThat(patientRepository.searchAccountedSummaries("\\%", PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void paginatesAndReportsTheTotal() {
        Page<PatientAccountSummaryProjection> first = patientRepository.searchAccountedSummaries(MARK, PageRequest.of(0, 1));
        Page<PatientAccountSummaryProjection> second = patientRepository.searchAccountedSummaries(MARK, PageRequest.of(1, 1));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(ids(first)).containsExactly(withAccountId);
        assertThat(ids(second)).containsExactly(otherWithAccountId);
    }
}
