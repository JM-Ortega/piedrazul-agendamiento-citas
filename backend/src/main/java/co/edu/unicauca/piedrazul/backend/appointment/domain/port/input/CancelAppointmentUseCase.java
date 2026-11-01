package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import java.util.UUID;

public interface CancelAppointmentUseCase {

    /**
     * Cancela una cita. El slot del médico queda libre automáticamente.
     *
     * @param appointmentId ID de la cita a cancelar
     */
        void cancel(UUID appointmentId,  UUID patientId);

}
