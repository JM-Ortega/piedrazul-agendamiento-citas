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
        String specialty,
        int appointmentInterval,
        List<Integer> workdays,
        LocalDate laborStart,
        LocalDate laborEnd,
        boolean status
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorDetailedResponse fromEntity(Doctor doctor) {
        return new DoctorDetailedResponse(
                doctor.getIdDoctor(),
                doctor.getFirstName() + " " + doctor.getLastName(),
                doctor.getSpecialty().toString(),
                doctor.getAppointmentInterval(),
                Optional.ofNullable(doctor.getSchedules())
                        .orElse(List.of())
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
