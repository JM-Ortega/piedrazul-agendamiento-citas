package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;

import java.util.UUID;

public record DoctorResponse(
        UUID id,
        String name,
        String specialty,
        boolean isActive
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static DoctorResponse fromEntity(Doctor doctor) {
        return new DoctorResponse(
                doctor.getIdDoctor(),
                doctor.getFirstName(),
                doctor.getSpecialty().toString(),
                doctor.isStatus()
        );
    }
}