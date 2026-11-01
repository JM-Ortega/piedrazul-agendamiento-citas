package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CheckExistenceByDocAndStateUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.util.UUID;

public class CheckExistenceByDocAndStateUseCaseImpl implements CheckExistenceByDocAndStateUseCase {

    private final AppointmentRepository appointmentRepository;

    public CheckExistenceByDocAndStateUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public boolean execute(UUID idDoctor, AppointmentState state) {
        return appointmentRepository.existsByDoctorAndState(idDoctor, state);
    }
}
