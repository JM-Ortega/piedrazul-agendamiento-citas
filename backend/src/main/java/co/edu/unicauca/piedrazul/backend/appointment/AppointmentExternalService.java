package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorPublicInfo;

import java.util.List;

public interface AppointmentExternalService {
	List<DoctorPublicInfo> getSpecialtiesWithDoctor();
}
