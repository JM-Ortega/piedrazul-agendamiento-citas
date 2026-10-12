package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import java.util.UUID;

public interface DoctorDailyCountView {
    UUID getDoctorId();
    Long getTotal();
}
