package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import java.util.UUID;

public interface CancelAppointmentUseCase {

    /**
     * Cancela una cita. El slot del médico queda libre automáticamente.
     *
     * @param appointmentId ID de la cita a cancelar
     * @param requesterId   ID del usuario que solicita la cancelación (para validar autorización)
     * @param isScheduler   true si el solicitante tiene rol SCHEDULER (puede cancelar cualquier cita)
     */

        void cancel(UUID appointmentId, UUID requesterId, boolean isScheduler);

}
