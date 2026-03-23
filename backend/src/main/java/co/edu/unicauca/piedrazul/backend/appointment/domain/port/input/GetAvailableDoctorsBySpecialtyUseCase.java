package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyDoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.util.List;

public interface GetAvailableDoctorsBySpecialtyUseCase {
    // Retorna un médico por especialidad que tenga slots disponibles
    // desde hoy en adelante
    List<SpecialtyDoctorResponse> getSpecialtiesWithDoctor();
}
