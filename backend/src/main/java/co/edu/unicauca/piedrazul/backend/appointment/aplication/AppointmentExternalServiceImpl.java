package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetSpecialtiesWithDoctorUseCase;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorPublicInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AppointmentExternalServiceImpl implements AppointmentExternalService {
	private final GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase;

	public AppointmentExternalServiceImpl(GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase) {
		this.getSpecialtiesWithDoctorUseCase = getSpecialtiesWithDoctorUseCase;
	}

	@Override
	public List<DoctorPublicInfo> getSpecialtiesWithDoctor() {
		return getSpecialtiesWithDoctorUseCase.getSpecialtiesWithDoctor();
	}
}
