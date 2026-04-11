package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.dto.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.domain.ClinicalHistory;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.infrastructure.persistence.ClinicalHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    public ClinicalHistoryResponse registerClinicalHistory(ClinicalHistoryRequest request) {

        if (repository.existsByIdAppointment(request.idAppointment())) {
            throw new RuntimeException("Esta cita ya tiene una historia clínica registrada");
        }

        AppointmentExternalData appointmentData = appointmentExternalService
                .getAppointmentData(request.idAppointment());

        if (!appointmentData.state().equals("ATENDIDA")) {
            throw new IllegalStateException("Solo se puede generar una historia clínica de una cita atendida");
        }

        LocalDateTime attendedAt = LocalDateTime.of(
                appointmentData.date(),
                appointmentData.startTime()
        );

        ClinicalHistory clinicalHistory = new ClinicalHistory(
                request.idAppointment(),
                appointmentData.idDoctor(),
                appointmentData.idPatient(),
                attendedAt,
                request.description()
        );

        ClinicalHistory saved = repository.save(clinicalHistory);

        return toResponse(saved, appointmentData);
    }

    @Override
    public ClinicalHistoryResponse getByAppointment(UUID idAppointment) {

        ClinicalHistory clinicalHistory = repository.findByIdAppointment(idAppointment)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No existe historia clínica para esta cita"));

        AppointmentExternalData appointmentData = appointmentExternalService
                .getAppointmentData(idAppointment);

        return toResponse(clinicalHistory, appointmentData);
    }

    @Override
    public List<ClinicalHistoryResponse> getHistoryByPatient(UUID idPatient) {
        return repository.findByIdPatient(idPatient)
                .stream()
                .map(ch -> {
                    AppointmentExternalData appointmentData = appointmentExternalService
                            .getAppointmentData(ch.getIdAppointment());
                    return toResponse(ch, appointmentData);
                })
                .toList();
    }

    private ClinicalHistoryResponse toResponse(ClinicalHistory ch,
                                               AppointmentExternalData appointmentData) {
        return new ClinicalHistoryResponse(
                ch.getIdClinicalHistory(),
                ch.getIdAppointment(),
                ch.getIdDoctor(),
                appointmentData.doctorName(),
                ch.getIdPatient(),
                ch.getAttendedAt(),
                ch.getDescription()
        );
    }
}