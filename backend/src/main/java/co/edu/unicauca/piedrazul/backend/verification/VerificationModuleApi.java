package co.edu.unicauca.piedrazul.backend.verification;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;

import java.util.UUID;

public interface VerificationModuleApi {

    void requestCode(
            String subject,
            VerificationPurpose purpose,
            String displayName,
            String phone,
            String email,
            UUID recipientId
    );

    boolean verifyCode(
            String subject,
            VerificationPurpose purpose,
            String code
    );
}