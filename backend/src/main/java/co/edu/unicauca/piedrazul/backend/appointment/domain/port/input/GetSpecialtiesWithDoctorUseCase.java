package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorPublicInfo;

import java.util.List;

public interface GetSpecialtiesWithDoctorUseCase {
    List<DoctorPublicInfo> getSpecialtiesWithDoctor();
}
