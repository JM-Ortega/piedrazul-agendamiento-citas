package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.*;

import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.UUID;

public class CancelAppointmentUseCaseImpl implements CancelAppointmentUseCase {

    private final AppointmentRepository appointmentRepository;

    public CancelAppointmentUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    @Transactional
    public void cancel(UUID  appointmentId, UUID patientId) {

        // Carga con bloqueo de fila: cargar, validar y guardar son una sola unidad transaccional
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId);

        if(patientId != null && !patientId.equals(appointment.getIdPatient())) {
            throw new AppointmentAccessDeniedException(
                    "El paciente con id " + patientId + " no puede cancelar la cita con id " + appointmentId
            );
        }

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
