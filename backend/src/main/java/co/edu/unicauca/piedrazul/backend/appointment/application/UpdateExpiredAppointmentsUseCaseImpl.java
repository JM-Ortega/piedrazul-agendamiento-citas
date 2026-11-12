package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateExpiredAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

public class UpdateExpiredAppointmentsUseCaseImpl implements UpdateExpiredAppointmentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateExpiredAppointmentsUseCaseImpl.class);

    private final AppointmentRepository appointmentRepository;

    public UpdateExpiredAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public void updateExpiredAppointments() {
        List<Appointment> appointments = appointmentRepository.findScheduledAppointmentsBefore(LocalDate.now());

        appointments.forEach(appointment -> {
            // Valida la transición contra el dominio (un candidato que ya no está AGENDADA sigue
            // deteniendo el lote con AppointmentStateConflictException, como antes).
            appointment.changeState(AppointmentState.NO_ASISTIO);

            // Escritura condicional atómica: solo cambia la fila si en base de datos sigue AGENDADA.
            // Si otra operación (cancelar, atender, no asistió) la movió entre la lectura de los
            // candidatos y esta actualización, no se sobrescribe su resultado.
            if (!appointmentRepository.markUnassistedIfScheduled(appointment.getIdAppointment())) {
                log.info("La cita {} cambió de estado antes del cierre nocturno; se conserva el estado vigente",
                        appointment.getIdAppointment());
            }
        });
    }
}
