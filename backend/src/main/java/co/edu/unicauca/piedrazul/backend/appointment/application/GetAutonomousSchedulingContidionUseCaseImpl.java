package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAutonomousSchedulingContidionUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;

public class GetAutonomousSchedulingContidionUseCaseImpl implements GetAutonomousSchedulingContidionUseCase {

    private final AppointmentConfigRepository appointmentConfigRepository;

    public GetAutonomousSchedulingContidionUseCaseImpl(AppointmentConfigRepository appointmentConfigRepository) {
        this.appointmentConfigRepository = appointmentConfigRepository;
    }

    @Override
    public boolean isAutonomousSchedulingEnabled() {
        return appointmentConfigRepository.isAutonomousSchedulingEnabled();
    }
}
