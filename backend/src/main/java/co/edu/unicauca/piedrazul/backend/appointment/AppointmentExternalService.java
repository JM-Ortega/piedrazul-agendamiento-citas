package co.edu.unicauca.piedrazul.backend.appointment;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AppointmentExternalService {
    List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state);
}
