package co.edu.unicauca.piedrazul.backend.verification.domain;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "verification_codes")
public class VerificationCode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationPurpose purpose;

    @Column(nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private int maxAttempts;

    @Column(nullable = false)
    private boolean used;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected VerificationCode() {
    }

    public VerificationCode(
            String subject,
            VerificationPurpose purpose,
            String codeHash,
            LocalDateTime expiresAt,
            int maxAttempts
    ) {
        this.subject = subject;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
        this.attempts = 0;
        this.used = false;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }

    public boolean hasAttemptsRemaining() {
        return attempts < maxAttempts;
    }

    public boolean isUsable(LocalDateTime now) {
        return !used && !isExpired(now) && hasAttemptsRemaining();
    }

    public void increaseAttempts() {
        this.attempts++;
    }

    public void markAsUsed() {
        this.used = true;
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

    public LocalDateTime getExpiresAt() {
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}