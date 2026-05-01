package co.edu.unicauca.piedrazul.backend.report.integration;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import org.springframework.stereotype.Component;

import java.util.UUID;
@Component
public class DoctorDataClient {

    private final DoctorExternalService doctorExternalService;

    public DoctorDataClient(DoctorExternalService doctorExternalService) {
        this.doctorExternalService = doctorExternalService;
    }

    public String getDoctorFullName(UUID idDoctor){
        return doctorExternalService.getDoctorName(idDoctor);
    }
}
