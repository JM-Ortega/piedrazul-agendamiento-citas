package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyTotalAppointmentDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorsAvailability;

import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface AppointmentExternalService {

    UUID getPattientIdByAppointmentId(UUID appointmentId);

    // Para el reporte de los medicos
    List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state);

    // Para el reporte de los agendadores
    List<SchedulerAppointmentSummary> findAllByDate(LocalDate date);

    boolean hasAvailableSlots(LocalDate date);

    boolean isNewPatient(UUID patientId);

    boolean hasScheduledAppointments(UUID doctorID);

    // Fechas (sin repetir, ordenadas) con citas agendadas del doctor dentro del rango, extremos inclusivos
    List<LocalDate> findScheduledAppointmentDates(UUID doctorId, LocalDate start, LocalDate end);

    Set<UUID> calculateDoctorsAvailability(List<DoctorsAvailability> doctorsAvailability);

    List<LocalDate> getAvailableDates(UUID doctorId);

    List<LocalTime> getAvailableSlots(UUID doctorId, LocalDate date);

    // ---- Estadísticas. Meses: 0 = enero ... 11 = diciembre ----

    /**
     * Citas por médico y mes.
     * @param state "AGENDADA" = todas las citas del sistema; "ATENDIDA" = solo las atendidas
     */
    DoctorAppointmentsCountDto getAppointmentsCountByDoctor(int year, String state);

    /** Citas en estado ATENDIDA por mes. */
    List<MonthlyTotalAppointmentDto> getAttendedAppointmentsCountByMonth(int year);

    /**
     * Citas por especialidad y mes.
     * @param state "AGENDADA" = todas las citas del sistema; "ATENDIDA" = solo las atendidas
     */
    SpecialtyAppointmentsCountDto getAppointmentsCountBySpecialty(int year, String state);

    /**
     * Total de citas del mes (todos los estados) y conteo del estado pedido.
     * @param state estado a contar (p. ej. "CANCELADA" o "NO_ASISTIO"); si es null cuenta CANCELADA + NO_ASISTIO
     */
    List<MonthlyCancellationDataDto> getCancellationData(int year, String state);

    /**
     * Total de citas por doctor en la fecha, sin contar las CANCELADA, ordenado por doctorId.
     * Solo aparecen doctores con al menos una cita ese día; fecha sin citas devuelve lista vacía.
     * @throws co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsDateException si date es null
     */
    List<DoctorDailyCountDto> getDoctorsAppointmentCountByDate(LocalDate date);
}
