package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateAppointmentStatusUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.util.UUID;

public class UpdateAppointmentStatusUseCaseImpl implements UpdateAppointmentStatusUseCase {
    private final AppointmentRepository appointmentRepository;

    public UpdateAppointmentStatusUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public void markAsAttended(UUID appointmentId) {
        // Obtener la cita por ID
        Appointment appointment = appointmentRepository.findById(appointmentId);

        // Actualizar el estado a ATENDIDA
        appointment.changeState(AppointmentState.ATENDIDA);

        // Guardar la cita actualizada
        appointmentRepository.save(appointment);
    }

    @Override
    public void markAsUnassisted(UUID appointmentId) {
        // Obtener la cita por ID
        Appointment appointment = appointmentRepository.findById(appointmentId);

        // Actualizar el estado a NO ASISTIDA
        appointment.changeState(AppointmentState.NO_ASISTIO);

        // Guardar la cita actualizada
        appointmentRepository.save(appointment);
    }
}
