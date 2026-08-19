package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.events.ClinicalHistoryCreatedEvent;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop.Auditable;
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

import java.util.UUID;

@Service
public class ClinicalHistoryExternalServiceImpl implements ClinicalHistoryExternalService {
    private final ClinicalHistoryRepository repository;
    private final AppointmentExternalService appointmentExternalService;
    private final ApplicationEventPublisher eventPublisher;
    private final SecurityContextExtractor securityExtractor;

    public ClinicalHistoryExternalServiceImpl(ClinicalHistoryRepository repository,
                                              AppointmentExternalService appointmentExternalService,
                                              ApplicationEventPublisher eventPublisher,
                                              SecurityContextExtractor securityExtractor) {
        this.repository = repository;
        this.appointmentExternalService = appointmentExternalService;
        this.eventPublisher = eventPublisher;
        this.securityExtractor = securityExtractor;
    }

    @Override
    public void registerClinicalHistory(ClinicalHistoryRequest request) {

        if (repository.existsByIdAppointment(request.appointmentId())) {
            throw new RuntimeException("Esta cita ya tiene una historia clínica registrada");
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
                ch.getId(),
                ch.getAttendedAt(),
                doctorName,
                ch.getDescription()
        );
    }
}