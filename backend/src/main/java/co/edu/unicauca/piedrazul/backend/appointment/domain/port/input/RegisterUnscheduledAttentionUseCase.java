package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.util.UUID;

public interface RegisterUnscheduledAttentionUseCase {
    UUID register(UUID idDoctor, PatientSchedulingContext context, SpecialtyCode specialty, String medicalCheckup);
}
