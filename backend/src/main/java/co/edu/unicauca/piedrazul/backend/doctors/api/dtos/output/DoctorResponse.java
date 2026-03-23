package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// Lo pidio Mar
public record DoctorResponse(
        String specialty,
        UUID id,
        String name,
        LocalDate laborEnd,
        List<Integer> workdays
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorResponse fromEntity(Doctor doctor) {
        return new DoctorResponse(
                doctor.getSpecialty().toString(),
                doctor.getIdDoctor(),
                doctor.getFirstName() + " " + doctor.getLastName(),
                doctor.getLaborEnd(),
                Optional.ofNullable(doctor.getSchedules())
                        .orElse(List.of())
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