package co.edu.unicauca.piedrazul.backend.appointment;

import java.util.UUID;
import java.time.LocalDate;
import java.util.List;

public interface AppointmentExternalService {

    AppointmentExternalData getAppointmentData(UUID idAppointment);

    UUID getPattientIdByAppointmentId(UUID appointmentId);
    
    //Para el reporte de los medicos
    List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state);

    //Para el reporte de los agendadores
    List<SchedulerAppointmentSummary> findAllByDate(LocalDate date);

    boolean hasAvailableSlots(LocalDate date);

    boolean isNewPatient(UUID patientId);
}
