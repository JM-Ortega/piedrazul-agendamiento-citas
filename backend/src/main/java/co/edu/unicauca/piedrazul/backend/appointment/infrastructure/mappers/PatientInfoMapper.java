package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.PatientRegistrationData;

public final class PatientInfoMapper {
    private  PatientInfoMapper() {
    }

    public static PatientRegistrationData toRegistrationData(PatientInfo patientInfo) {
        return  new PatientRegistrationData(
                patientInfo.getDocumentType(),
                patientInfo.getDocumentNumber(),
                patientInfo.getFirstName(),
                patientInfo.getLastName(),
                patientInfo.getPhone(),
                patientInfo.getGender(),
                patientInfo.getBirthDate(),
                patientInfo.getEmail(),
                patientInfo.getGuardianPhone()
        );
    }

}
