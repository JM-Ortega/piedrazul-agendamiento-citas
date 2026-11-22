package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import java.util.UUID;

public interface CancelAppointmentUseCase {

    /**
     * Cancela una cita. El slot del médico queda libre automáticamente.
     *
     * @param appointmentId ID de la cita a cancelar
     * @param patientId     paciente autenticado que cancela su propia cita (se valida que le
     *                      pertenezca y no se avisa); {@code null} cuando cancela el personal, que
     *                      genera un aviso al paciente tras el commit
     */
    void cancel(UUID appointmentId, UUID patientId);

    /**
     * Cancelación interna de un reagendamiento: mismas validaciones y bloqueo, sin aviso de
     * cancelación (el reagendamiento publica el suyo).
     */
    void cancelForReschedule(UUID appointmentId);

}
