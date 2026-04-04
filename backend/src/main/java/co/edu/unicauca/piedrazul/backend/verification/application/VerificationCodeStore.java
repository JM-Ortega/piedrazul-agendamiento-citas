package co.edu.unicauca.piedrazul.backend.verification.application;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.domain.VerificationCode;

import java.util.Optional;

public interface VerificationCodeStore {

    Optional<VerificationCode> findLatestActive(String subject, VerificationPurpose purpose);

    VerificationCode save(VerificationCode verificationCode);
}