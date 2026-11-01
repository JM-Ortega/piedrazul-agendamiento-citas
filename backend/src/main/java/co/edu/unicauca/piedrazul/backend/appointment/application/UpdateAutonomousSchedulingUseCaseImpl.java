package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateAutonomousSchedulingUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;

public class UpdateAutonomousSchedulingUseCaseImpl implements UpdateAutonomousSchedulingUseCase {

    private final AppointmentConfigRepository appointmentConfigRepository;

    public UpdateAutonomousSchedulingUseCaseImpl(AppointmentConfigRepository appointmentConfigRepository) {
        this.appointmentConfigRepository = appointmentConfigRepository;
    }

    @Override
    public void setEnabledAutonomous(boolean enabled) {
        appointmentConfigRepository.setAutonomousSchedulingEnabled(enabled);
    }
}
