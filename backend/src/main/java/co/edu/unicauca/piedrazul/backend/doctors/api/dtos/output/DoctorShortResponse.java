package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import java.util.*;

// Lo pido Nicolle
public record DoctorShortResponse(
        UUID id,
        String name,
        String specialty
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorShortResponse fromEntity(Doctor doctor) {
        return new DoctorShortResponse(
                doctor.getIdDoctor(),
                doctor.getFirstName() + " " + doctor.getLastName(),
                doctor.getSpecialty().toString()
        );
    }
}
