package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import org.springframework.stereotype.Component;

@Component
public class CitaDtoMapper {
    private final PatientConsultPort patientConsultPort;

    public CitaDtoMapper(PatientConsultPort patientConsultPort) {
        this.patientConsultPort = patientConsultPort;
    }

    // Appointment -> AppointmentResponse
    public AppointmentResponse toResponse(Appointment appointment) {
        patientConsultPort.findById(appointment.getIdPatient());
        return new AppointmentResponse(
                appointment.getIdAppointment(),
                appointment.getDate(),
                appointment.getStartTime().getTime(),
                appointment.getAppointmentState(),
                appointment.getDoctorName(),
                appointment.getSpecialty(),
                patientConsultPort.findById(appointment.getIdPatient()).getFirstName(),
                patientConsultPort.findById(appointment.getIdPatient()).getLastName(),
                patientConsultPort.findById(appointment.getIdPatient()).getDocumentNumber()
        );
    }

    // AppointmentRequest -> PatientInfo
    public PatientInfo toPatientInfo(AppointmentRequest request) {
        return PatientInfo.of(
                request.getDocumentType(),
                request.getDocumentNumber(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getGender(),
                request.getBirthDate(),
                request.getEmail(),
                request.getGuardianPhone()
        );
    }
}
