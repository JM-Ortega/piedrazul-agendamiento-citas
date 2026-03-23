package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.util.List;

public interface AppointmentExternalService {
	List<DoctorResponse> getSpecialtiesWithDoctor();
}
