package co.edu.unicauca.piedrazul.backend.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizedThrowableTest {

    private static final String MARKER = "SYN0000001";

    private static List<Throwable> chainOf(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    @Test
    void keepsTheClassAndFramesOfEveryCauseWithoutTheirMessages() {
        IllegalArgumentException root = new IllegalArgumentException("raíz " + MARKER);
        IllegalStateException middle = new IllegalStateException("medio " + MARKER, root);
        RuntimeException top = new RuntimeException("arriba " + MARKER, middle);

        List<Throwable> chain = chainOf(SanitizedThrowable.of(top));

        assertThat(chain).extracting(Throwable::getMessage).containsExactly(
                RuntimeException.class.getName(),
                IllegalStateException.class.getName(),
                IllegalArgumentException.class.getName());
        assertThat(chain.getFirst().getStackTrace()).isEqualTo(top.getStackTrace());
        assertThat(chain.get(1).getStackTrace()).isEqualTo(middle.getStackTrace());
        assertThat(chain.get(2).getStackTrace()).isEqualTo(root.getStackTrace());
    }

    @Test
    void anExceptionWithoutCauseHasASingleEntry() {
        assertThat(chainOf(SanitizedThrowable.of(new RuntimeException((String) null)))).hasSize(1);
    }

    @Test
    void dropsSuppressedExceptions() {
        RuntimeException top = new RuntimeException("arriba");
        top.addSuppressed(new RuntimeException("suprimida " + MARKER));

        assertThat(SanitizedThrowable.of(top).getSuppressed()).isEmpty();
    }

    @Test
    void aCauseCycleEndsTheChain() {
        RuntimeException first = new RuntimeException("primera " + MARKER);
        RuntimeException second = new RuntimeException("segunda " + MARKER, first);
        first.initCause(second);

        assertThat(chainOf(SanitizedThrowable.of(first))).hasSize(2);
    }

    @Test
    void aVeryLongChainIsBounded() {
        Throwable current = new RuntimeException("raíz " + MARKER);
        for (int i = 0; i < 100; i++) {
            current = new RuntimeException("nivel " + MARKER, current);
        }

        assertThat(chainOf(SanitizedThrowable.of(current))).hasSize(16);
    }

    @Test
    void keepsAWellFormedSqlState() {
        SQLException failure = new SQLException("Detail: Key (identification)=(" + MARKER + ")", "23505");

        assertThat(SanitizedThrowable.of(failure).getMessage())
                .isEqualTo(SQLException.class.getName() + " [SQLState=23505]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYN0000001", "2350", "235o5", "23505\nx", " 3505"})
    void ignoresAMalformedSqlState(String sqlState) {
        assertThat(SanitizedThrowable.of(new SQLException("fallo", sqlState)).getMessage())
                .isEqualTo(SQLException.class.getName());
    }

    @Test
    void ignoresAMissingSqlState() {
        assertThat(SanitizedThrowable.of(new SQLException("fallo")).getMessage())
                .isEqualTo(SQLException.class.getName());
    }
}
