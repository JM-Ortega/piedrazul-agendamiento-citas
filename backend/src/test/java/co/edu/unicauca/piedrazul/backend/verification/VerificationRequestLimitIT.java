package co.edu.unicauca.piedrazul.backend.verification;

import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeRequestLimitException;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeRepository;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * El límite de solicitudes de código sobre PostgreSQL real: las ventanas se cuentan con la
 * fecha de creación guardada y las solicitudes simultáneas no se saltan el límite.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class,
        VerificationRequestLimitIT.TestBeans.class
})
// Sin transacción envolvente del test: cada solicitud confirma como en producción.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerificationRequestLimitIT extends PostgresIntegrationSupport {

    private static final VerificationPurpose PURPOSE = VerificationPurpose.LINK_PATIENT_ACCOUNT;
    private static final UUID RECIPIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TestConfiguration
    static class TestBeans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    @MockitoBean
    private VerificationCodeSender verificationCodeSender;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private JpaVerificationCodeRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String subject;

    @BeforeEach
    void setUp() {
        subject = "doc-" + UUID.randomUUID();
    }

    private void requestCode() {
        verificationService.requestCode(subject, PURPOSE, "Ana Ruiz", "3001234567", "ana@correo.com", RECIPIENT);
    }

    /** Un código emitido hace {@code ago}; los usados no ocupan el índice de código activo. */
    private void seedIssued(Duration ago, boolean used) {
        VerificationCode code = new VerificationCode(subject, PURPOSE, "hash",
                Instant.now().plus(Duration.ofMinutes(5)), 5);
        if (used) {
            code.invalidate();
        }
        UUID id = repository.saveAndFlush(code).getId();
        jdbcTemplate.update("UPDATE piedrazul.verification_code SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(ago)), id);
    }

    private void verifyCodesSent(int expected) {
        verify(verificationCodeSender, times(expected))
                .sendCode(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    private long codesInDatabase() {
        return repository.countBySubjectAndPurposeAndCreatedAtGreaterThanEqual(subject, PURPOSE, Instant.EPOCH);
    }

    @Test
    void aSecondRequestWithinAMinuteIsRejectedAndSendsNothing() {
        requestCode();

        assertThrows(VerificationCodeRequestLimitException.class, this::requestCode);

        verifyCodesSent(1);
        assertEquals(1, codesInDatabase());
    }

    @Test
    void codesIssuedEarlierInTheHourCountTowardsTheLimit() {
        for (int i = 0; i < 5; i++) {
            seedIssued(Duration.ofMinutes(10 + i), true);
        }

        assertThrows(VerificationCodeRequestLimitException.class, this::requestCode);

        verifyCodesSent(0);
    }

    @Test
    void codesOlderThanAnHourDoNotCount() {
        for (int i = 0; i < 5; i++) {
            seedIssued(Duration.ofMinutes(61 + i), true);
        }

        assertDoesNotThrow(this::requestCode);

        verifyCodesSent(1);
    }

    @Test
    void concurrentRequestsSendASingleCode() throws Exception {
        // Un código activo de hace dos minutos: las solicitudes compiten por su bloqueo.
        seedIssued(Duration.ofMinutes(2), false);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    requestCode();
                    return true;
                } catch (VerificationCodeRequestLimitException rejected) {
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(30, TimeUnit.SECONDS));

        int accepted = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                accepted++;
            }
        }

        assertEquals(1, accepted, "solo una solicitud simultánea debe emitir un código");
        verifyCodesSent(1);
    }
}
