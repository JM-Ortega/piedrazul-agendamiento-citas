package co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaVerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findFirstBySubjectAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            String subject,
            VerificationPurpose purpose
    );
}