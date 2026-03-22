package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
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

    @Override
    public String doctorsName(UUID idDoctor) {
        return doctorRepository.findByIdDoctor(idDoctor).getFirstName() + " " + doctorRepository.findByIdDoctor(idDoctor).getLastName();
    }
}
