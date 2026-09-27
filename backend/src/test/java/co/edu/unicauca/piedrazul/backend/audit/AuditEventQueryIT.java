package co.edu.unicauca.piedrazul.backend.audit;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventQuery;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventView;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence.AuditEventRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La consulta es SQL nativo (cruce con person, unaccent, ESCAPE, orden y paginación), así que
 * solo una base real puede comprobar que devuelve lo que se espera. Cada test corre en su propia
 * transacción, que se revierte.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditEventRepositoryAdapter.class)
class AuditEventQueryIT extends PostgresIntegrationSupport {

    // Marca única en los apellidos para no depender de otras personas de la base.
    private static final String MARK = "Qzkw";
    private static final Instant BASE = Instant.parse("2031-05-10T12:00:00Z");

    @Autowired
    private AuditEventRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbc;

    private String docPrefix;
    private String anaDocument;
    private String carlosDocument;
    private UUID anaAccount;
    private UUID carlosAccount;
    private UUID accountWithoutPerson;
    private UUID r1, r2, r3, r4, r5;

    @BeforeEach
    void setUp() {
        docPrefix = String.valueOf(System.nanoTime()).substring(0, 8);
        anaDocument = docPrefix + "01";
        carlosDocument = docPrefix + "02";
        anaAccount = UUID.randomUUID();
        carlosAccount = UUID.randomUUID();
        accountWithoutPerson = UUID.randomUUID();

        person(anaAccount, anaDocument, "Ana", "Núñez " + MARK);
        person(carlosAccount, carlosDocument, "Carlos", "Díaz " + MARK);

        r1 = event(0, anaAccount.toString(), "[DOCTOR]", "CITA_AGENDADA", "EXITOSO", "Cita", "c-1");
        r2 = event(1, carlosAccount.toString(), "[ADMIN]", "USUARIO_CREADO", "EXITOSO", "Usuario", "u-1");
        r3 = event(2, anaAccount.toString(), "[DOCTOR]", "PACIENTE_MODIFICADO", "DENEGADO", "Paciente", "p-1");
        r4 = event(3, accountWithoutPerson.toString(), "[ADMIN]", "ROL_ASIGNADO", "DENEGADO", "Usuario", "N/A");
        r5 = event(4, "anonymous", "N/A", "USUARIO_CREADO", "EXITOSO", "Usuario", "u-2");
    }

    private void person(UUID account, String document, String firstName, String lastName) {
        jdbc.update("""
                INSERT INTO piedrazul.person (id, user_id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, ?, 'CEDULA', ?, ?, ?, '3001234567', ?)
                """, UUID.randomUUID(), account, document, firstName, lastName, document + "@example.com");
    }

    private UUID event(int hoursAfterBase, String actorId, String roles, String action, String outcome,
                       String targetType, String targetId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.audit_event (id, occurred_at, actor_id, actor_role, action_code, outcome,
                                                   target_entity_type, target_entity_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, Timestamp.from(BASE.plus(Duration.ofHours(hoursAfterBase))), actorId, roles, action, outcome,
                targetType, targetId, "corr-" + hoursAfterBase);
        return id;
    }

    private static AuditEventQuery query(Instant from, Instant to, AuditAction action, AuditOutcome outcome,
                                         String search, String actorId, int page, int size) {
        return new AuditEventQuery(from, to, action, outcome, search, actorId, null, null, page, size);
    }

    /** Ventana que abarca solo los cinco registros de este test. */
    private AuditEventQuery window() {
        return query(BASE.minusSeconds(1), BASE.plus(Duration.ofHours(5)), null, null, null, null, 0, 50);
    }

    private static List<UUID> ids(AuditEventPage page) {
        return page.content().stream().map(AuditEventView::id).toList();
    }

    // ---- orden y fechas --------------------------------------------------------

    @Test
    void theNewestRecordComesFirst() {
        assertThat(ids(repository.findByCriteria(window()))).containsExactly(r5, r4, r3, r2, r1);
    }

    @Test
    void theDateRangeIsInclusiveOnBothEnds() {
        var page = repository.findByCriteria(query(BASE.plus(Duration.ofHours(1)), BASE.plus(Duration.ofHours(3)),
                null, null, null, null, 0, 50));

        assertThat(ids(page)).containsExactly(r4, r3, r2);
    }

    @Test
    void withOnlyTheStartTheRangeIsOpenAtTheEnd() {
        var page = repository.findByCriteria(query(BASE.plus(Duration.ofHours(3)), null, null, null, null, null, 0, 50));

        assertThat(ids(page)).startsWith(r5, r4);
        assertThat(ids(page)).doesNotContain(r3, r2, r1);
    }

    @Test
    void withOnlyTheEndTheRangeIsOpenAtTheStart() {
        var page = repository.findByCriteria(query(null, BASE.plus(Duration.ofHours(1)), null, null, null, null, 0, 50));

        assertThat(ids(page)).contains(r2, r1).doesNotContain(r3, r4, r5);
        assertThat(ids(page).indexOf(r2)).isLessThan(ids(page).indexOf(r1));
    }

    @Test
    void withoutAnyDateTheWholeHistoryIsListedNewestFirst() {
        var all = ids(repository.findByCriteria(query(null, null, null, null, null, null, 0, 200)));

        assertThat(all).contains(r1, r2, r3, r4, r5);
        assertThat(all.indexOf(r5)).isLessThan(all.indexOf(r1));
    }

    // ---- acción y resultado ----------------------------------------------------

    @Test
    void itFiltersByAction() {
        var page = repository.findByCriteria(query(window().from(), window().to(),
                AuditAction.USUARIO_CREADO, null, null, null, 0, 50));

        assertThat(ids(page)).containsExactly(r5, r2);
    }

    @Test
    void itFiltersByOutcome() {
        var page = repository.findByCriteria(query(window().from(), window().to(),
                null, AuditOutcome.DENEGADO, null, null, 0, 50));

        assertThat(ids(page)).containsExactly(r4, r3);
    }

    @Test
    void theFiltersCombine() {
        var page = repository.findByCriteria(query(window().from(), window().to(),
                AuditAction.PACIENTE_MODIFICADO, AuditOutcome.DENEGADO, null, null, 0, 50));

        assertThat(ids(page)).containsExactly(r3);
        assertThat(repository.findByCriteria(query(window().from(), window().to(),
                AuditAction.PACIENTE_MODIFICADO, AuditOutcome.EXITOSO, null, null, 0, 50)).content()).isEmpty();
    }

    // ---- búsqueda por nombre o usuario ----------------------------------------

    @Test
    void itSearchesByNameIgnoringCaseAndAccents() {
        for (String term : new String[]{"nunez " + MARK, "NÚÑEZ", "ana nunez", "ANA NUÑEZ QZKW"}) {
            var page = repository.findByCriteria(query(window().from(), window().to(), null, null, term, null, 0, 50));

            assertThat(ids(page)).as("término: " + term).containsExactly(r3, r1);
        }
    }

    @Test
    void itSearchesByThePartOfTheNameThatSpansFirstAndLastName() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, "carlos diaz", null, 0, 50));

        assertThat(ids(page)).containsExactly(r2);
    }

    @Test
    void itSearchesByTheKeycloakUsernameWhichIsTheDocument() {
        var exact = repository.findByCriteria(query(window().from(), window().to(), null, null, carlosDocument, null, 0, 50));
        var partial = repository.findByCriteria(query(window().from(), window().to(), null, null, docPrefix, null, 0, 50));

        assertThat(ids(exact)).containsExactly(r2);
        assertThat(ids(partial)).containsExactly(r3, r2, r1);
    }

    @Test
    void aSearchNeverMatchesActorsWithoutPerson() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, "anonymous", null, 0, 50));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void wildcardsInTheSearchAreLiteral() {
        for (String term : new String[]{"%", "_", "\\", "a%"}) {
            var page = repository.findByCriteria(query(window().from(), window().to(), null, null, term, null, 0, 50));

            assertThat(page.totalElements()).as("término: " + term).isZero();
        }
    }

    @Test
    void theSearchCombinesWithTheOtherFilters() {
        var page = repository.findByCriteria(query(window().from(), window().to(),
                AuditAction.CITA_AGENDADA, AuditOutcome.EXITOSO, "nunez", null, 0, 50));

        assertThat(ids(page)).containsExactly(r1);
    }

    @Test
    void everyWordMustAppearButTheyNeitherHaveToBeAdjacentNorInOrder() {
        UUID mariaAccount = UUID.randomUUID();
        person(mariaAccount, docPrefix + "03", "María José", "Pérez Gómez " + MARK);
        UUID mariaRow = event(5, mariaAccount.toString(), "[DOCTOR]", "CITA_AGENDADA", "EXITOSO", "Cita", "c-9");
        Instant to = BASE.plus(Duration.ofHours(6));

        for (String term : new String[]{"maria jose", "jose gomez", "gomez maria", "MARÍA GÓMEZ", "perez jose " + MARK}) {
            assertThat(ids(repository.findByCriteria(query(window().from(), to, null, null, term, null, 0, 50))))
                    .as("término: " + term).containsExactly(mariaRow);
        }
    }

    @Test
    void aWordThatAppearsNowhereExcludesTheRecordEvenIfTheOthersMatch() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, "ana zzzz", null, 0, 50));

        assertThat(page.content()).isEmpty();
    }

    @Test
    void oneWordMayMatchTheNameAndAnotherTheDocument() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null,
                "carlos " + carlosDocument, null, 0, 50));

        assertThat(ids(page)).containsExactly(r2);
    }

    @Test
    void theSameWordRepeatedDoesNotChangeTheResult() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, "ana ana ana", null, 0, 50));

        assertThat(ids(page)).containsExactly(r3, r1);
    }

    // ---- quién actuó -----------------------------------------------------------

    @Test
    void itFiltersByTheExactAccountId() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, null,
                accountWithoutPerson.toString(), 0, 50));

        assertThat(ids(page)).containsExactly(r4);
    }

    @Test
    void theActorDataComesFromThePersonOfTheAccount() {
        AuditEventView view = repository.findByCriteria(window()).content().stream()
                .filter(v -> v.id().equals(r3)).findFirst().orElseThrow();

        assertThat(view.actorId()).isEqualTo(anaAccount.toString());
        assertThat(view.actorName()).isEqualTo("Ana Núñez " + MARK);
        assertThat(view.actorUsername()).isEqualTo(anaDocument);
        assertThat(view.actorRoles()).isEqualTo("[DOCTOR]");
        assertThat(view.action()).isEqualTo("PACIENTE_MODIFICADO");
        assertThat(view.outcome()).isEqualTo("DENEGADO");
        assertThat(view.targetEntityType()).isEqualTo("Paciente");
        assertThat(view.targetEntityId()).isEqualTo("p-1");
        assertThat(view.correlationId()).isEqualTo("corr-2");
        assertThat(view.timestamp()).isEqualTo(BASE.plus(Duration.ofHours(2)));
    }

    @Test
    void anAccountWithoutPersonOrAnAnonymousActorHasNoNameNorUsername() {
        var page = repository.findByCriteria(window());

        for (UUID id : List.of(r4, r5)) {
            AuditEventView view = page.content().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
            assertThat(view.actorName()).isNull();
            assertThat(view.actorUsername()).isNull();
        }
    }

    // ---- los cambios de nombre o de documento -----------------------------------

    @Test
    void whenThePersonChangesNameAndDocumentEveryOldRecordShowsTheNewOnes() {
        String newDocument = docPrefix + "99";
        jdbc.update("UPDATE piedrazul.person SET first_name = 'Anabel', last_name = ?, identification = ? WHERE user_id = ?",
                "Pérez " + MARK, newDocument, anaAccount);

        var oldRows = repository.findByCriteria(query(window().from(), window().to(), null, null, null,
                anaAccount.toString(), 0, 50));

        assertThat(oldRows.content()).extracting(AuditEventView::id).containsExactly(r3, r1);
        assertThat(oldRows.content()).allSatisfy(v -> {
            assertThat(v.actorName()).isEqualTo("Anabel Pérez " + MARK);
            assertThat(v.actorUsername()).isEqualTo(newDocument);
        });

        // Se encuentran por el nombre nuevo, todas sus filas juntas, y ya no por el viejo.
        assertThat(ids(repository.findByCriteria(query(window().from(), window().to(), null, null,
                "anabel perez", null, 0, 50)))).containsExactly(r3, r1);
        assertThat(repository.findByCriteria(query(window().from(), window().to(), null, null,
                "ana nunez", null, 0, 50)).content()).isEmpty();
        assertThat(repository.findByCriteria(query(window().from(), window().to(), null, null,
                anaDocument, null, 0, 50)).content()).isEmpty();
    }

    // ---- paginación ------------------------------------------------------------

    @Test
    void itPaginatesAndReportsTheTotal() {
        var first = repository.findByCriteria(query(window().from(), window().to(), null, null, null, null, 0, 2));
        var second = repository.findByCriteria(query(window().from(), window().to(), null, null, null, null, 1, 2));
        var third = repository.findByCriteria(query(window().from(), window().to(), null, null, null, null, 2, 2));

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(ids(first)).containsExactly(r5, r4);
        assertThat(ids(second)).containsExactly(r3, r2);
        assertThat(ids(third)).containsExactly(r1);
    }

    @Test
    void theTotalCountsOnlyWhatMatchesTheSearch() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, MARK, null, 0, 1));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).hasSize(1);
    }

    @Test
    void aPageBeyondTheEndIsEmptyButKeepsTheTotal() {
        var page = repository.findByCriteria(query(window().from(), window().to(), null, null, null, null, 9, 10));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(5);
    }
}
