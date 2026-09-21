package co.edu.unicauca.piedrazul.backend.medicalCheckup.application;

import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.intput.CheckupUpdateRequest;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.events.ClinicalHistoryCreatedEvent;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.exception.MedicalCheckupAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.exception.MedicalCheckupNotEditableException;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.exception.MedicalCheckupNotFoundException;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.MedicalCheckupExternalService;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.internal.MedicalCheckupRequest;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.output.MedicalCheckupResponse;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.domain.MedicalCheckup;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.infrastructure.persistence.MedicalCheckupRepository;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class MedicalCheckupExternalServiceImpl implements MedicalCheckupExternalService {
    private final MedicalCheckupRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityContextExtractor securityExtractor;

    public MedicalCheckupExternalServiceImpl(MedicalCheckupRepository repository,
                                             ApplicationEventPublisher eventPublisher,
                                             SecurityContextExtractor securityExtractor) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.securityExtractor = securityExtractor;
    }

    @Override
    public void registerClinicalHistory(MedicalCheckupRequest request) {

        if (repository.existsByIdAppointment(request.appointmentId())) {
            throw new MedicalCheckupAlreadyExistsException("Esta cita ya tiene un control medico registrado");
        }

        MedicalCheckup save = repository.save(new MedicalCheckup(
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
    public Page<MedicalCheckupResponse> getHistoryByPatient(UUID idPatient, Pageable pageable) {
        return repository.findByIdPatient(idPatient, pageable)
                .map(ch -> toResponse(ch, ch.getDoctor_name()));
    }

    @Override
    public MedicalCheckupResponse updateCheckUp(UUID idClinicalHistory,
                                                CheckupUpdateRequest request) {

        MedicalCheckup medicalCheckup = repository.findById(idClinicalHistory)
                .orElseThrow(() -> new MedicalCheckupNotFoundException(idClinicalHistory.toString()));

        if (!medicalCheckup.getAttendedAt().equals(LocalDate.now())) {
            throw new MedicalCheckupNotEditableException("Solo se puede editar una historia clínica del día actual");
        }

        medicalCheckup.updateDescription(request.description());
        MedicalCheckup saved = repository.save(medicalCheckup);

        return toResponse(saved, saved.getDoctor_name());
    }

    private MedicalCheckupResponse toResponse(MedicalCheckup ch, String doctorName) {
        return new MedicalCheckupResponse(
                ch.getId(),
                ch.getAttendedAt(),
                doctorName,
                ch.getDescription()
        );
    }
}