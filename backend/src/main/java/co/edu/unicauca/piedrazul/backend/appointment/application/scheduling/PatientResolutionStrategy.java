package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

public interface PatientResolutionStrategy {
    ResolvedPatient resolve(PatientSchedulingContext context);
}