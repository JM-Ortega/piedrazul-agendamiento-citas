package co.edu.unicauca.piedrazul.backend.clinicalHistory.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input.ClinicalHistoryRequest;
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
    public ClinicalHistoryResponse registerClinicalHistory(ClinicalHistoryRequest request) {

        if (repository.existsByIdAppointment(request.idAppointment())) {
            throw new RuntimeException("Esta cita ya tiene una historia clínica registrada");
        }

        AppointmentExternalData appointmentData = appointmentExternalService
                .getAppointmentData(request.idAppointment());

        if (!appointmentData.state().equals("ATENDIDA")) {
            throw new IllegalStateException("Solo se puede generar una historia clínica de una cita atendida");
        }



        ClinicalHistory clinicalHistory = new ClinicalHistory(
                request.idAppointment(),
                appointmentData.idDoctor(),
                appointmentData.idPatient(),
                appointmentData.date(),
                request.description()
        );

        ClinicalHistory saved = repository.save(clinicalHistory);

        return toResponse(saved, appointmentData.doctorName());
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