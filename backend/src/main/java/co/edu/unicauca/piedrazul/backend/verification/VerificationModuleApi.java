package co.edu.unicauca.piedrazul.backend.verification;

import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;

public interface VerificationModuleApi {

    void requestCode(
            String subject,
            VerificationPurpose purpose,
            String destination
    );

    boolean verifyCode(
            String subject,
            VerificationPurpose purpose,
            String code
    );
}