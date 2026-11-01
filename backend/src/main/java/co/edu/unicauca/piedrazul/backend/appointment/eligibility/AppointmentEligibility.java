package co.edu.unicauca.piedrazul.backend.appointment.eligibility;

import java.util.UUID;

/**
 * Lo único que otro módulo necesita saber de una cita para decidir si aún debe avisar sobre ella.
 * Es una lectura del estado confirmado, no una reserva: una cancelación confirmada después de la
 * lectura puede coexistir con lo que el llamador haga a continuación.
 */
public interface AppointmentEligibility {

    /**
     * Lee en la base, cada vez, el estado confirmado de la cita. Falso si no existe o ya no está
     * AGENDADA. Una cancelación aún sin confirmar no cuenta, y la lectura no la espera.
     */
    boolean isScheduled(UUID appointmentId);
}
