package co.edu.unicauca.piedrazul.backend.doctors.model.services;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Doctor;
import org.springframework.stereotype.Service;

import java.util.UUID;

//Servicio para peticiones externas
@Service
public class DoctorExternalServiceImpl implements DoctorExternalService {
    private final DoctorService doctorService;

    public DoctorExternalServiceImpl(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    @Override
    public boolean existDoctor(UUID idDoctor){
        Doctor d = doctorService.getDoctorById(idDoctor);
        if(d != null){
            return  true;
        }else {
            return false;
        }
    }
}
