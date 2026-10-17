package co.edu.unicauca.piedrazul.backend.statistics.integration;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DoctorNamesClient {

    private final DoctorExternalService doctorExternalService;

    public DoctorNamesClient(DoctorExternalService doctorExternalService) {
        this.doctorExternalService = doctorExternalService;
    }

    /** Nombre completo de todos los doctores, indexado por su id. */
    public Map<UUID, String> getDoctorNames() {
        return doctorExternalService.getDoctorNamesByIds();
    }

    public List<UUID> getActiveDoctorIds() {
        return doctorExternalService.getActiveDoctorIds();
    }
}
