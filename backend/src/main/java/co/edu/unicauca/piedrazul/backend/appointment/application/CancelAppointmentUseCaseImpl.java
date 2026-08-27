package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.CancelAppointmentNotAllowedException;

import java.time.LocalDate;
import java.util.UUID;

public class CancelAppointmentUseCaseImpl implements CancelAppointmentUseCase {

    private final AppointmentRepository appointmentRepository;

    public CancelAppointmentUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public void cancel(UUID  appointmentId) {

        Appointment appointment = appointmentRepository.findById(appointmentId);

        // No se pueden cancelar citas pasadas (ya manejamos que sean ATENDIDA o NO_ASISTIO)
        //pero por doble seguridad
        if (appointment.getDate().isBefore(LocalDate.now())) {
            throw new CancelAppointmentNotAllowedException(
                    "No se puede cancelar una cita de una fecha pasada"
            );
        }

        appointment.cancel();
        appointmentRepository.save(appointment);
    }
}
