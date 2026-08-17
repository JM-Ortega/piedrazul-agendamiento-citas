package co.edu.unicauca.piedrazul.backend.verification;

import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import co.edu.unicauca.piedrazul.backend.verification.exception.InvalidVerificationCodeException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeAlreadyUsedException;
import co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeBlockedException;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueba las garantías de verificación que dependen de transacciones y
 * concurrencia reales.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class,
        VerificationTransactionalIT.TestBeans.class
})
// Sin transacción envolvente del test: aquí se comprueban fronteras reales.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VerificationTransactionalIT extends PostgresIntegrationSupport {

    private static final VerificationPurpose PURPOSE = VerificationPurpose.LINK_PATIENT_ACCOUNT;
    private static final String RAW_CODE = "123456";

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private String subject;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        subject = "doc-" + UUID.randomUUID();
    }

    private VerificationCode seedCode(int maxAttempts, int alreadyFailed) {
        VerificationCode code = new VerificationCode(
                subject, PURPOSE, passwordEncoder.encode(RAW_CODE),
                Instant.now().plusSeconds(300), maxAttempts);

        for (int i = 0; i < alreadyFailed; i++) {
            code.increaseAttempts();
        }

        return repository.saveAndFlush(code);
    }

    private int attemptsInDatabase(UUID id) {
        return repository.findById(id).orElseThrow().getAttempts();
    }

    private boolean usedInDatabase(UUID id) {
        return repository.findById(id).orElseThrow().isUsed();
    }

    @Test
    void failedAttemptSurvivesCallerRollback() {
        VerificationCode code = seedCode(5, 0);

        tx.execute(status -> {
            assertThrows(InvalidVerificationCodeException.class,
                    () -> verificationService.verifyCode(subject, PURPOSE, "999999"));
            status.setRollbackOnly();
            return null;
        });

        assertEquals(1, attemptsInDatabase(code.getId()),
                "el incremento debe estar comprometido pese al rollback exterior");
    }

    @Test
    void codeCanOnlyBeConsumedOnce() {
        VerificationCode code = seedCode(5, 0);

        VerifiedCode verified = verificationService.verifyCode(subject, PURPOSE, RAW_CODE);
        assertFalse(usedInDatabase(code.getId()), "verificar no debe consumir");

        tx.executeWithoutResult(status -> verificationService.consumeCode(verified));
        assertTrue(usedInDatabase(code.getId()));

        assertThrows(VerificationCodeAlreadyUsedException.class, () ->
                tx.executeWithoutResult(status -> verificationService.consumeCode(verified)));
    }

    // Cubre también la exclusividad que necesita la confirmación de acceso antes de
    // tocar el proveedor de identidad, sin duplicar ese flujo completo.
    @Test
    void onlyOneConcurrentConsumptionSucceeds() throws Exception {
        seedCode(5, 0);
        VerifiedCode verified = verificationService.verifyCode(subject, PURPOSE, RAW_CODE);

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        tx.executeWithoutResult(status -> verificationService.consumeCode(verified));
                        consumed.incrementAndGet();
                    } catch (VerificationCodeAlreadyUsedException expected) {
                        // Único desenlace admisible para las perdedoras: cualquier otra
                        // excepción escapa al Future y hace fallar el test.
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, consumed.get(), "solo una petición puede consumir el código");
        assertEquals(threads - 1, rejected.get());
    }

    @Test
    void concurrentFailedAttemptsAreSerializedWithoutLostUpdates() throws Exception {
        VerificationCode code = seedCode(5, 4); // queda 1 intento

        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger invalid = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        verificationService.verifyCode(subject, PURPOSE, "999999");
                    } catch (InvalidVerificationCodeException e) {
                        invalid.incrementAndGet();
                    } catch (VerificationCodeBlockedException e) {
                        blocked.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, invalid.get(), "solo una petición consume el intento restante");
        assertEquals(threads - 1, blocked.get(), "el resto debe quedar bloqueado");
        assertEquals(5, attemptsInDatabase(code.getId()), "ningún incremento se pierde por carrera");
    }

    @Test
    void correctCodeIsRejectedOnceAttemptsAreExhausted() {
        seedCode(5, 5);

        assertThrows(VerificationCodeBlockedException.class,
                () -> verificationService.verifyCode(subject, PURPOSE, RAW_CODE));
    }

    @Test
    void consumeRequiresAnActiveTransaction() {
        VerificationCode code = seedCode(5, 0);
        VerifiedCode verified = verificationService.verifyCode(subject, PURPOSE, RAW_CODE);

        assertThrows(IllegalTransactionStateException.class,
                () -> verificationService.consumeCode(verified));

        assertFalse(usedInDatabase(code.getId()), "no debe haber consumo accidental");

        tx.executeWithoutResult(status -> verificationService.consumeCode(verified));
        assertTrue(usedInDatabase(code.getId()));
    }
}
