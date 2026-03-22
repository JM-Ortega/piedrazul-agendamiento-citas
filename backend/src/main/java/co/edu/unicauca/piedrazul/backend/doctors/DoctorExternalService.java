package co.edu.unicauca.piedrazul.backend.doctors;

import java.util.UUID;

public interface DoctorExternalService {
    boolean existDoctor(UUID idDoctor);
}
