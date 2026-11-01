package co.edu.unicauca.piedrazul.backend.doctors.domain;

import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Periodo de descanso de un doctor: rango de fechas inclusivo en el que no se agenda con él.
 * También es el historial de descansos, por eso un descanso en curso se recorta en vez de borrarse.
 */
@Getter
@NoArgsConstructor
@Entity
@Table(name = "doctor_time_off", schema = "piedrazul")
public class DoctorTimeOff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DoctorTimeOff(UUID doctorId, LocalDate startDate, LocalDate endDate, String reason) {
        if (startDate == null || endDate == null) {
            throw new DateConflictException("El descanso debe tener fecha de inicio y de fin");
        }
        if (endDate.isBefore(startDate)) {
            throw new DateConflictException("La fecha de fin del descanso no puede ser anterior a la de inicio");
        }
        this.doctorId = doctorId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.createdAt = Instant.now();
    }

    // Los extremos son inclusivos
    public boolean covers(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public boolean overlaps(LocalDate start, LocalDate end) {
        return !startDate.isAfter(end) && !endDate.isBefore(start);
    }

    public boolean hasEnded(LocalDate today) {
        return endDate.isBefore(today);
    }

    // Un descanso que empieza hoy o después todavía no tuvo efecto: se puede borrar sin perder historial
    public boolean isUpcoming(LocalDate today) {
        return !startDate.isBefore(today);
    }

    public TimeOffStatus statusOn(LocalDate today) {
        if (hasEnded(today)) {
            return TimeOffStatus.FINALIZADO;
        }
        return isUpcoming(today) ? TimeOffStatus.PROGRAMADO : TimeOffStatus.EN_CURSO;
    }

    // Recorta un descanso en curso: hoy y los días siguientes quedan libres
    public void endBefore(LocalDate today) {
        this.endDate = today.minusDays(1);
    }
}
