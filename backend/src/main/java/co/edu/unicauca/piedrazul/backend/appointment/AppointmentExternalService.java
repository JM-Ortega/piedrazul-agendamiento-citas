package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorsAvailability;

import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface AppointmentExternalService {

    AppointmentExternalData getAppointmentData(UUID idAppointment);

    UUID getPattientIdByAppointmentId(UUID appointmentId);

    // Para el reporte de los medicos
    List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state);

    // Para el reporte de los agendadores
    List<SchedulerAppointmentSummary> findAllByDate(LocalDate date);

    boolean hasAvailableSlots(LocalDate date);

    boolean isNewPatient(UUID patientId);

    boolean hasScheduledAppointments(UUID doctorID);

    Set<UUID> calculateDoctorsAvailability(List<DoctorsAvailability> doctorsAvailability);

    List<LocalDate> getAvailableDates(UUID doctorId);

    List<LocalTime> getAvailableSlots(UUID doctorId, LocalDate date);

}
