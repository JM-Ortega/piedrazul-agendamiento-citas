package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.internal.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence.ClinicalHistoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ClinicalHistoryExternalServiceImpl implements ClinicalHistoryExternalService {
    private final ClinicalHistoryRepository repository;
    private final AppointmentExternalService appointmentExternalService;

    public ClinicalHistoryExternalServiceImpl(ClinicalHistoryRepository repository,
                                              AppointmentExternalService appointmentExternalService) {
        this.repository = repository;
        this.appointmentExternalService = appointmentExternalService;
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

    @Override
    public List<ClinicalHistoryResponse> getHistoryByPatient(UUID idPatient) {
        return repository.findByIdPatient(idPatient)
                .stream()
                .map(ch -> {
                    AppointmentExternalData appointmentData = appointmentExternalService
                            .getAppointmentData(ch.getIdAppointment());
                    return toResponse(ch, appointmentData.doctorName());
                })
                .toList();
    }

    private ClinicalHistoryResponse toResponse(ClinicalHistory ch,
                                               String doctorName) {
        return new ClinicalHistoryResponse(
                ch.getAttendedAt(),
                doctorName,
                ch.getDescription()
        );
    }
}