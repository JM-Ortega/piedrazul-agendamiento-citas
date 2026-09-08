package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.time.LocalDate;
import java.util.*;

public record DoctorAvailableResponse(
                List<String> specialty,
                UUID id,
                String name,
                List<LocalDate> availableDates) {
        // Si el paciente es nuevo solo muestra como especialida de doctor la de tera
        // neural aunque tenga otras
        public static DoctorAvailableResponse fromEntity(
                        Doctor doctor,
                        String name,
                        boolean isNewPatient,
                        List<LocalDate> availableDates) {
                // Filtrar las especialidades a nivel de DTO sin tocar la entidad
                List<String> specialties = doctor.getSpecialties().stream()
                                .filter(s -> !isNewPatient || s.getCode() == SpecialtyCode.TERAPIA_NEURAL)
                                .map(s -> s.getCode().name())
                                .toList();

                return new DoctorAvailableResponse(
                                specialties,
                                doctor.getPersonId(),
                                name,
                                availableDates);
        }
}
