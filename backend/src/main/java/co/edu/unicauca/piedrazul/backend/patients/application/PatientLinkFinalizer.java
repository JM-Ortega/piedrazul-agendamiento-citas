package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Agrupa las escrituras de finalización de la habilitación de acceso que deben
 * ejecutarse dentro de unidades transaccionales definidas.
 *
 * <p>Esto permite que las operaciones externas de quien orquesta queden fuera de
 * dichas transacciones.
 */
@Component
public class PatientLinkFinalizer {

    private final VerificationModuleApi verificationModuleApi;
    private final PersonExternalService personExternalService;
    private final PatientRepository patientRepository;

    public PatientLinkFinalizer(
            VerificationModuleApi verificationModuleApi,
            PersonExternalService personExternalService,
            PatientRepository patientRepository
    ) {
        this.verificationModuleApi = verificationModuleApi;
        this.personExternalService = personExternalService;
        this.patientRepository = patientRepository;
    }

    /**
     * Consume el código en exclusiva.
     *
     * @throws co.edu.unicauca.piedrazul.backend.verification.exception.VerificationCodeAlreadyUsedException
     * si ya estaba consumido
     */
    @Transactional
    public void consumeOtp(VerifiedCode verifiedCode) {
        verificationModuleApi.consumeCode(verifiedCode);
    }

    @Transactional
    public void linkUserAccount(UUID personId, UUID userId) {
        personExternalService.linkUserId(personId, userId);
    }

    /**
     * Consume el código y registra el paciente en la misma transacción: si el
     * registro falla, el código no queda consumido.
     */
    @Transactional
    public void consumeOtpAndRegisterPatient(
            VerifiedCode verifiedCode,
            PersonSummary person,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        verificationModuleApi.consumeCode(verifiedCode);
        patientRepository.save(PatientFactory.create(person, sex, birthDate, guardianPhone));
    }
}
