package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop.Auditable;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.internal.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence.ClinicalHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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
    @Auditable(
            action = AuditAction.HISTORIA_CLINICA_CONSULTADA,
            targetEntityType = "ClinicalHistory",
            targetIdExpression = "#idPatient"
    )
    public Page<ClinicalHistoryResponse> getHistoryByPatient(
            UUID idPatient,
            Pageable pageable) {

        return repository.findByIdPatient(idPatient, pageable)
                .map(ch -> {
                    AppointmentExternalData appointmentData =
                            appointmentExternalService
                                    .getAppointmentData(ch.getIdAppointment());

                    return toResponse(ch, appointmentData.doctorName());
                });
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