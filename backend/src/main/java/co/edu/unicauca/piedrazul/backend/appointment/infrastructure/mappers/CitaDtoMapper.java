package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import org.springframework.stereotype.Component;

@Component
public class CitaDtoMapper {

    // Appointment -> AppointmentResponse
    public AppointmentResponse toResponse(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getIdAppointment(),
                appointment.getDate(),
                appointment.getStartTime().getTime(),
                appointment.getAppointmentState(),
                appointment.getDoctorName(),
                appointment.getSpecialty(),
                appointment.getPatientInfo().getFirstName(),
                appointment.getPatientInfo().getLastName(),
                appointment.getPatientInfo().getDocumentNumber()
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
