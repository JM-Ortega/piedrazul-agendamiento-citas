package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.time.LocalDate;
import java.util.*;

// Lo pidio Mar
public record DoctorResponse(
        List<String> specialty,
        UUID id,
        String name,
        LocalDate laborEnd,
        LocalDate laborStart,
        int bookingWindowWeeks,
        List<Integer> workdays
) {
    public static DoctorResponse fromEntity(Doctor doctor, String name, boolean isNewPatient) {
        // Filtrar las especialidades a nivel de DTO sin tocar la entidad
        List<String> specialties = doctor.getSpecialties().stream()
                .filter(s -> !isNewPatient || s.getCode() == SpecialtyCode.TERAPIA_NEURAL)
                .map(s -> s.getCode().name())
                .toList();

        return new DoctorResponse(
                specialties,
                doctor.getPersonId(),
                name,
                doctor.getLaborEnd(),
                doctor.getLaborStart(),
                doctor.getBookingWindowWeeks(),
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

    // Sobrecarga por defecto para mantener retrocompatibilidad
    public static DoctorResponse fromEntity(Doctor doctor, String name) {
        return fromEntity(doctor, name, false);
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