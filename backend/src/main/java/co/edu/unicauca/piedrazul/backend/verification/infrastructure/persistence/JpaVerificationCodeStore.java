package co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeStore;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    public Optional<VerificationCode> findLatestActiveForUpdate(String subject, VerificationPurpose purpose) {
        List<VerificationCode> found =
                repository.findActiveForUpdate(subject, purpose, PageRequest.of(0, 1));

        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public int consumeIfUnused(UUID codeId) {
        return repository.consumeIfUnused(codeId);
    }

    @Override
    public VerificationCode save(VerificationCode verificationCode) {
        return repository.saveAndFlush(verificationCode);
    }
}
