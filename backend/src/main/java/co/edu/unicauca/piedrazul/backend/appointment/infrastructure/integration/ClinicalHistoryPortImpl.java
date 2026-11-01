package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.MedicalCheckupExternalService;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.internal.MedicalCheckupRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
public class ClinicalHistoryPortImpl implements ClinicalHistoryPort {
    private final MedicalCheckupExternalService externalService;

    public ClinicalHistoryPortImpl(MedicalCheckupExternalService externalService) {
        this.externalService = externalService;
    }

    @Override
    public void registerClinicalHistory(
            UUID appointmentId,
            UUID idPatient,
            String doctorName,
            String description,
            LocalDate attendedAt) {
        externalService.registerClinicalHistory(
            new MedicalCheckupRequest(idPatient, appointmentId, description, doctorName, attendedAt)
        );
    }
}
