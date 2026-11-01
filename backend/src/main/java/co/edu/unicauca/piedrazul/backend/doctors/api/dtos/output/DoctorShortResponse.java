package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import java.util.*;

// Lo pido Nicolle
public record DoctorShortResponse(
        UUID id,
        String name,
        List<String> specialties
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorShortResponse fromEntity(Doctor doctor, String name) {
        return new DoctorShortResponse(
                doctor.getPersonId(),
                name,
                doctor.getSpecialties().stream()
                        .map(s -> s.getCode().name())
                        .toList()
        );
    }
}
