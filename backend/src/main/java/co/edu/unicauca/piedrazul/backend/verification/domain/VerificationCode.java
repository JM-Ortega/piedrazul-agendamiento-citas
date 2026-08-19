package co.edu.unicauca.piedrazul.backend.verification.domain;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "verification_code",
        indexes = {
                @Index(name = "idx_verification_code_purpose", columnList = "purpose_code")
        }
)
public class VerificationCode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose_code", nullable = false, length = 40)
    private VerificationPurpose purpose;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private int maxAttempts;

    @Column(nullable = false)
    private boolean used;

    @Column(nullable = false)
    private Instant createdAt;

    protected VerificationCode() {
    }

    public VerificationCode(
            String subject,
            VerificationPurpose purpose,
            String codeHash,
            Instant expiresAt,
            int maxAttempts
    ) {
        this.subject = subject;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
        this.attempts = 0;
        this.used = false;
        this.createdAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean hasAttemptsRemaining() {
        return attempts < maxAttempts;
    }

    public boolean isUsable(Instant now) {
        return !used && !isExpired(now) && hasAttemptsRemaining();
    }

    public void increaseAttempts() {
        this.attempts++;
    }

    public void invalidate() {
        this.used = true;
    }

    public UUID getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public VerificationPurpose getPurpose() {
        return purpose;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public boolean isUsed() {
        return used;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}