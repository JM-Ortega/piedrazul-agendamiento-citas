package co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaVerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findFirstBySubjectAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            String subject,
            VerificationPurpose purpose
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT v
            FROM VerificationCode v
            WHERE v.subject = :subject
              AND v.purpose = :purpose
              AND v.used = false
            ORDER BY v.createdAt DESC
            """)
    List<VerificationCode> findActiveForUpdate(
            @Param("subject") String subject,
            @Param("purpose") VerificationPurpose purpose,
            Pageable pageable
    );

    @Modifying
    @Query("""
            UPDATE VerificationCode v
            SET v.used = true
            WHERE v.id = :id
              AND v.used = false
            """)
    int consumeIfUnused(@Param("id") UUID id);
}
