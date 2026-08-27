package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.util.List;
import java.util.UUID;

public interface GetSpecialtiesWithDoctorUseCase {
    List<DoctorResponse> getSpecialtiesWithDoctor(UUID patientId);
}
