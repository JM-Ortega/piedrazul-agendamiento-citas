package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento de dominio publicado cuando una cita queda agendada exitosamente.
 * Es la API pública del módulo `citas` hacia otros módulos (ej. audit).
 */
public record CitaAgendadaEvent(
        UUID citaId,
        UUID pacienteId,
        UUID medicoId,
        String username,
        String rol,
        Instant fechaHoraCita,
        String correlationId,
        Instant timestamp
) {
    public static CitaAgendadaEvent of(AppointmentEntity cita, String username, String rol, String correlationId) {
        LocalDateTime fechaHoraLocal = cita.getDate().atTime(cita.getStartTime());

        Instant fechaHoraCitaInstant = fechaHoraLocal.atZone(java.time.ZoneId.systemDefault()).toInstant();

        return new CitaAgendadaEvent(
                cita.getIdAppointment(),
                cita.getIdPatient(),
                cita.getIdDoctor(),
                username,
                rol,
                fechaHoraCitaInstant,
                correlationId,
                Instant.now()
        );
    }
}
