package co.edu.unicauca.piedrazul.backend.appointment;

import java.util.UUID;
import java.time.LocalDate;
import java.util.List;

public interface AppointmentExternalService {

    AppointmentExternalData getAppointmentData(UUID idAppointment);

    List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state);

    UUID getPattientIdByAppointmentId(UUID appointmentId);
}
