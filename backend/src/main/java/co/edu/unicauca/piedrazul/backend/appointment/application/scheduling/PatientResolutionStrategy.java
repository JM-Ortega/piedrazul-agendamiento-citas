package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;

public interface PatientResolutionStrategy {
    ResolvedPatient resolve(PatientSchedulingContext context);
}