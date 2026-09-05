package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record DoctorsAvailability(
        UUID personId,
        int bookingWindowWeeks,
        int appointmentInterval,
        Set<ScheduleAvailability> schedules
) {
    public static DoctorsAvailability fromEntity(Doctor doctor) {
        return new DoctorsAvailability(
                doctor.getPersonId(),
                doctor.getBookingWindowWeeks(),
                doctor.getAppointmentInterval(),
                doctor.getSchedules().stream()
                        .map(ScheduleAvailability::fromEntity)
                        .collect(Collectors.toSet())
        );
    }
}
