package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.intput.CheckUpUpdateRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.events.ClinicalHistoryCreatedEvent;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.exception.MedicalCheckupAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.exception.MedicalCheckupNotEditableException;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.exception.MedicalCheckupNotFoundException;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.internal.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence.ClinicalHistoryRepository;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class ClinicalHistoryExternalServiceImpl implements ClinicalHistoryExternalService {
    private final ClinicalHistoryRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityContextExtractor securityExtractor;

    public ClinicalHistoryExternalServiceImpl(ClinicalHistoryRepository repository,
                                              ApplicationEventPublisher eventPublisher,
                                              SecurityContextExtractor securityExtractor) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.securityExtractor = securityExtractor;
    }

    @Override
    public void registerClinicalHistory(ClinicalHistoryRequest request) {

        if (repository.existsByIdAppointment(request.appointmentId())) {
            throw new MedicalCheckupAlreadyExistsException("Esta cita ya tiene un control medico registrado");
        }

        ClinicalHistory save = repository.save(new ClinicalHistory(
                request.patientId(),
                request.appointmentId(),
                request.attendedAt(),
                request.doctorName(),
                request.description()
        ));

        String actorId = securityExtractor.currentActorId();
        String actorRoles = securityExtractor.currentActorRoles();

        eventPublisher.publishEvent(
                ClinicalHistoryCreatedEvent.of(
                        save.getId(),
                        actorId,
                        actorRoles,
                        MDC.get("correlationId")
                )
        );
    }

    @Override
    public Page<ClinicalHistoryResponse> getHistoryByPatient(UUID idPatient, Pageable pageable) {
        return repository.findByIdPatient(idPatient, pageable)
                .map(ch -> toResponse(ch, ch.getDoctor_name()));
    }

    @Override
    public ClinicalHistoryResponse updateCheckUp(UUID idClinicalHistory,
                                                 CheckUpUpdateRequest request) {

        ClinicalHistory clinicalHistory = repository.findById(idClinicalHistory)
                .orElseThrow(() -> new MedicalCheckupNotFoundException(idClinicalHistory.toString()));

        if (!clinicalHistory.getAttendedAt().equals(LocalDate.now())) {
            throw new MedicalCheckupNotEditableException("Solo se puede editar una historia clínica del día actual");
        }

        clinicalHistory.updateDescription(request.description());
        ClinicalHistory saved = repository.save(clinicalHistory);

        return toResponse(saved, saved.getDoctor_name());
    }

    private ClinicalHistoryResponse toResponse(ClinicalHistory ch, String doctorName) {
        return new ClinicalHistoryResponse(
                ch.getId(),
                ch.getAttendedAt(),
                doctorName,
                ch.getDescription()
        );
    }
}