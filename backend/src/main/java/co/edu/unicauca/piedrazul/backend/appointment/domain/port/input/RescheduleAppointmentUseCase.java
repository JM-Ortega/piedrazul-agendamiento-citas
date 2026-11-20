package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.time.LocalDate;
import java.util.UUID;

public interface RescheduleAppointmentUseCase {

    /**
     * Reagenda una cita: cancela la original y agenda una nueva (origen MANUAL) para el mismo
     * paciente, en una sola transacción. Si el agendamiento falla, la original no queda cancelada.
     *
     * @param appointmentId     cita original
     * @param requesterDoctorId médico autenticado que reagenda; solo puede reagendar sus propias
     *                          citas. {@code null} para el agendador (sin restricción)
     * @return la cita nueva
     */
    Appointment reschedule(
            UUID appointmentId,
            UUID requesterDoctorId,
            UUID newDoctorId,
            SpecialtyCode specialty,
            LocalDate date,
            AppointmentTime startTime,
            UUID performedBy);
}
