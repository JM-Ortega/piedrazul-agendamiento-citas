package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.internal.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence.ClinicalHistoryRepository;
import org.springframework.stereotype.Service;

@Service
public class ClinicalHistoryExternalServiceImpl implements ClinicalHistoryExternalService {

    private final ClinicalHistoryRepository repository;

    public ClinicalHistoryExternalServiceImpl(ClinicalHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void registerClinicalHistory(ClinicalHistoryRequest request) {

        if (repository.existsByIdAppointment(request.appointmentId())) {
            throw new RuntimeException("Esta cita ya tiene una historia clínica registrada");
        }

        repository.save(new ClinicalHistory(
                request.patientId(),
                request.appointmentId(),
                request.attendedAt(),
                request.doctorName(),
                request.description()
        ));
    }
}