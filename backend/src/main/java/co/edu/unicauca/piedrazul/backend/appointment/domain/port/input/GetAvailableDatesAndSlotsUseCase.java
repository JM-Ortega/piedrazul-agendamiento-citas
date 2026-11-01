package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface GetAvailableDatesAndSlotsUseCase {
    List<LocalDate> getAvailableDates(UUID idDoctor);

    List<LocalTime> getAvailableSlots(UUID idDoctor, LocalDate date);
}
