package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;

import java.time.LocalDate;
import java.util.*;

// Lo pidio Mar
public record DoctorResponse(
        List<String> specialty,
        UUID id,
        String name,
        LocalDate laborEnd,
        List<Integer> workdays
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorResponse fromEntity(Doctor doctor, String name) {
        return new DoctorResponse(
                doctor.getSpecialties().stream()
                        .map(s -> s.getCode().name())
                        .toList(),
                doctor.getPersonId(),
                name,
                doctor.getLaborEnd(),
                Optional.ofNullable(doctor.getSchedules())
                        .orElse(new HashSet<>())
                        .stream()
                        .map(Schedule::getWorkday)
                        .filter(Objects::nonNull)
                        .map(DoctorResponse::toWorkdayNumber)
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList()
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