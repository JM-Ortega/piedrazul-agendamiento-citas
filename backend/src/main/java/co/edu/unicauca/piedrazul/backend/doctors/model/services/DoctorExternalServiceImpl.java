package co.edu.unicauca.piedrazul.backend.doctors.model.services;

import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

// Lombok
@RequiredArgsConstructor

//Servicio para peticiones externas
@Service
public class DoctorExternalServiceImpl implements DoctorExternalService {

    private final DoctorRepository doctorRepository;

    @Override
    public boolean existDoctor(UUID idDoctor) {
        return doctorRepository.existsById(idDoctor);
    }
}
