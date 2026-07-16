package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;

import java.time.LocalDate;
import java.util.*;

// Lo pidió Nicolle
public record   DoctorDetailedResponse(
        UUID id,
        String name,
        List<String> specialty,
        int appointmentInterval,
        List<Integer> workdays,
        LocalDate laborStart,
        LocalDate laborEnd,
        boolean status
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorDetailedResponse fromEntity(Doctor doctor, String name) {
        return new DoctorDetailedResponse(
                doctor.getPersonId(),
                name,
                doctor.getSpecialties().stream()
                        .map(s -> s.getCode().name())
                        .toList(),
                doctor.getAppointmentInterval(),
                Optional.ofNullable(doctor.getSchedules())
                        .orElse(new HashSet<>())
                        .stream()
                        .map(Schedule::getWorkday)
                        .filter(Objects::nonNull)
                        .map(DoctorDetailedResponse::toWorkdayNumber)
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList(),
                doctor.getLaborStart(),
                doctor.getLaborEnd(),
                doctor.isStatus()
        );
    }

    private static int toWorkdayNumber(Workday workday) {
        return switch (workday) {
            case LUNES -> 1;
            case MARTES -> 2;
            case MIERCOLES -> 3;
            case JUEVES -> 4;
            case VIERNES -> 5;
        };
    }
}
