package co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeStore;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaVerificationCodeStore implements VerificationCodeStore {

    private final JpaVerificationCodeRepository repository;

    public JpaVerificationCodeStore(JpaVerificationCodeRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<VerificationCode> findLatestActive(String subject, VerificationPurpose purpose) {
        return repository.findFirstBySubjectAndPurposeAndUsedFalseOrderByCreatedAtDesc(subject, purpose);
    }

    @Override
    public VerificationCode save(VerificationCode verificationCode) {
        return repository.saveAndFlush(verificationCode);
    }
}