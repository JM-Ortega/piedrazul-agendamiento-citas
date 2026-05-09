package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.util.List;

public interface GetSpecialtiesWithDoctorUseCase {
    List<DoctorResponse> getSpecialtiesWithDoctor();
}
