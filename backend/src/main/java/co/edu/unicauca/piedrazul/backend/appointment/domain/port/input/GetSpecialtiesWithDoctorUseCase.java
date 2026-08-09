package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.UUID;

public interface GetSpecialtiesWithDoctorUseCase {
    List<DoctorResponse> getSpecialtiesWithDoctor(@Nullable UUID patientId);
}
