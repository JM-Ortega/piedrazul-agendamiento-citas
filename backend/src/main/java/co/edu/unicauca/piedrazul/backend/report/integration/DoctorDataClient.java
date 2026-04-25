package co.edu.unicauca.piedrazul.backend.report.integration;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;

import java.util.UUID;

public class DoctorDataClient {

    private final DoctorExternalService doctorExternalService;

    public DoctorDataClient(DoctorExternalService doctorExternalService) {
        this.doctorExternalService = doctorExternalService;
    }

    public String getDoctorFullName(UUID idDoctor){
        return doctorExternalService.getDoctorName(idDoctor);
    }
}
