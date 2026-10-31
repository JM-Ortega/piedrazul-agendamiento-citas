package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.time.LocalDate;

/**
 * Estado completo que debe quedar en el paciente. Es un reemplazo total, no un
 * cambio parcial: todo campo obligatorio debe venir informado.
 *
 * <p>Cuando quien edita no puede cambiar el documento, {@code identificationType}
 * e {@code identification} vienen nulos y se completan con
 * {@link #withDocument(IdentificationType, String)}.
 */
public record UpdatePatientCommand(
        IdentificationType identificationType,
        String identification,
        String firstName,
        String lastName,
        String phone,
        String email,
        PatientSex sex,
        LocalDate birthDate,
        String guardianPhone
) {

    public UpdatePatientCommand withDocument(IdentificationType type, String document) {
        return new UpdatePatientCommand(
                type, document, firstName, lastName, phone, email, sex, birthDate, guardianPhone
        );
    }
}
