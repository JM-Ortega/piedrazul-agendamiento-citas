package co.edu.unicauca.piedrazul.backend.medicalCheckup.exception;

import org.springframework.http.HttpStatus;

public class MedicalCheckupNotFoundException extends MedicalCheckupBusinessException {
    public MedicalCheckupNotFoundException(String id) {
        super("No existe un control medico para el id: " + id,
                "MEDICAL_CHECK_UP_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
