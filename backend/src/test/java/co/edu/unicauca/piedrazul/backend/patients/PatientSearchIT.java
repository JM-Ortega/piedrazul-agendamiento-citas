package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientSummaryProjection;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
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
 * Las consultas del listado son SQL nativo (unaccent, trigramas, ESCAPE), así
 * que solo una base real puede comprobar que devuelven lo que se espera.
 *
 * <p>Cada test corre en su propia transacción que se revierte. Los nombres llevan
 * una marca única para no depender de lo que hayan dejado otras clases de test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PersonExternalServiceImp.class)
class PatientSearchIT extends PostgresIntegrationSupport {

    private static final String MARK = "Zqxv";
    private static final LocalDate ADULT = LocalDate.now().minusYears(30);

    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private PersonExternalServiceImp personExternalService;

    @Autowired
    private PatientRepository patientRepository;

    private UUID carlos;
    private UUID alvaro;
    private UUID beatriz;
    private String carlosDocument;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime()).substring(0, 8);
        carlosDocument = "1" + unique + "1";

        // Se insertan fuera de orden alfabético a propósito.
        carlos = patient("Carlos", MARK + " Díaz", carlosDocument);
        alvaro = patient("Álvaro", MARK + " Núñez", "2" + unique + "2");
        beatriz = patient("Beatriz", MARK + " Ríos", "3" + unique + "3");
        patientRepository.flush();
    }

    private UUID patient(String first, String last, String document) {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, document, first, last, "3001234567", first.toLowerCase() + "@example.com", null);
        patientRepository.save(new Patient(person.id(), Sex.FEMENINO, ADULT, null));
        return person.id();
    }

    private List<UUID> ids(Page<PatientSummaryProjection> page) {
        return page.getContent().stream().map(PatientSummaryProjection::getId).toList();
    }

    @Test
    void searchByNameReturnsMatchesOrderedByNameIgnoringAccentsInTheOrder() {
        Page<PatientSummaryProjection> page = patientRepository.searchSummaries(MARK, PageRequest.of(0, 10));

        // Álvaro se ordena como "alvaro", no después de la z por su tilde.
        assertThat(ids(page)).containsExactly(alvaro, beatriz, carlos);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void searchIgnoresCaseAndAccentsInBothDirections() {
        assertThat(ids(patientRepository.searchSummaries("alvaro zqxv", PageRequest.of(0, 10))))
                .containsExactly(alvaro);
        assertThat(ids(patientRepository.searchSummaries("ÁLVARO", PageRequest.of(0, 10))))
                .contains(alvaro);
        assertThat(ids(patientRepository.searchSummaries("nunez", PageRequest.of(0, 10))))
                .contains(alvaro);
    }

    @Test
    void searchMatchesFullNameAcrossFirstAndLastName() {
        assertThat(ids(patientRepository.searchSummaries("carlos zqxv d", PageRequest.of(0, 10))))
                .containsExactly(carlos);
    }

    @Test
    void searchByPartOfTheDocumentNumber() {
        String fragment = carlosDocument.substring(2, 8);

        Page<PatientSummaryProjection> page = patientRepository.searchSummaries(fragment, PageRequest.of(0, 10));

        assertThat(ids(page)).contains(carlos);
        PatientSummaryProjection row = page.getContent().stream()
                .filter(r -> r.getId().equals(carlos)).findFirst().orElseThrow();
        assertThat(row.getIdentification()).isEqualTo(carlosDocument);
        assertThat(row.getFirstName()).isEqualTo("Carlos");
        assertThat(row.getLastName()).isEqualTo(MARK + " Díaz");
    }

    @Test
    void escapedWildcardsAreLiteralAndDoNotMatchEverything() {
        assertThat(patientRepository.searchSummaries("\\%", PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(patientRepository.searchSummaries("\\_", PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(patientRepository.searchSummaries("\\\\", PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void searchPaginatesAndReportsTheTotal() {
        Page<PatientSummaryProjection> first = patientRepository.searchSummaries(MARK, PageRequest.of(0, 2));
        Page<PatientSummaryProjection> second = patientRepository.searchSummaries(MARK, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(ids(first)).containsExactly(alvaro, beatriz);
        assertThat(ids(second)).containsExactly(carlos);
    }

    @Test
    void searchWithoutMatchesIsEmpty() {
        Page<PatientSummaryProjection> page = patientRepository.searchSummaries("nadie-se-llama-asi", PageRequest.of(0, 10));

        assertThat(page.isEmpty()).isTrue();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void listingWithoutSearchIncludesEveryPatientOrderedByName() {
        Page<PatientSummaryProjection> page = patientRepository.findAllSummaries(PageRequest.of(0, 1000));

        List<UUID> all = ids(page);
        assertThat(all).contains(alvaro, beatriz, carlos);
        assertThat(all.indexOf(alvaro)).isLessThan(all.indexOf(beatriz));
        assertThat(all.indexOf(beatriz)).isLessThan(all.indexOf(carlos));
        assertThat(page.getTotalElements()).isEqualTo(patientRepository.count());
    }

    @Test
    void personsThatAreNotPatientsAreNotListed() {
        String unique = String.valueOf(System.nanoTime()).substring(0, 8);
        PersonSummary notAPatient = personExternalService.createPerson(
                IdentificationType.CEDULA, "9" + unique + "9", "Doctor", MARK + " Sinpaciente",
                "3001234567", "doc@example.com", null);
        patientRepository.flush();

        assertThat(ids(patientRepository.searchSummaries(MARK, PageRequest.of(0, 10))))
                .doesNotContain(notAPatient.id());
    }
}
