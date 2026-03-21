package co.edu.unicauca.piedrazul.backend.doctors.model.services;

import java.util.UUID;

public interface DoctorExternalService {
    boolean existDoctor(UUID idDoctor);
}
