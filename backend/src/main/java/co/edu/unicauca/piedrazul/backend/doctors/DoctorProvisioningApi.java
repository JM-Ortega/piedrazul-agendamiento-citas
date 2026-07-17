package co.edu.unicauca.piedrazul.backend.doctors;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;

import java.util.UUID;

public interface DoctorProvisioningApi {

    void createDoctor(UUID personId, CreateDoctorRequest request);

}