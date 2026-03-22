package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SpecialtyDoctor(
        Specialty specialty,
        UUID doctorId,
        String doctorName,
        LocalDate fechaFinalTrabajo,
        List<Integer> workDays
) {
    public static SpecialtyDoctor from(Specialty specialty, Doctor doctor) {
        return new SpecialtyDoctor(
                specialty,
                doctor.getIdDoctor(),
                doctor.getFirstName() + " " + doctor.getLastName(),
                doctor.getLaborEnd(),
                Optional.ofNullable(doctor.getSchedules())
                        .orElse(List.of())
                        .stream()
                        .map(Schedule::getWorkday)
                        .filter(Objects::nonNull)
                        .map(SpecialtyDoctor::toWorkdayNumber)
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
