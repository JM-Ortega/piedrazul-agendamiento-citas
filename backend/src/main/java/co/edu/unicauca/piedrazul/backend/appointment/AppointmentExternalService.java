package co.edu.unicauca.piedrazul.backend.appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AppointmentExternalService {

    //Para el reporte de los medicos
    List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state);

    //Para el reporte de los agendadores
    List<SchedulerAppointmentSummary> findAllByDate(LocalDate date);

    boolean hasAvailableSlots(LocalDate date);

    boolean isNewPatient(UUID patientId);
}
