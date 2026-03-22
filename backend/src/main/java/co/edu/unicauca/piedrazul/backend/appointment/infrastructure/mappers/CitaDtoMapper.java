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
                appointment.getIdDoctor(),
                appointment.getPatientInfo().getFirstName() + " " + appointment.getPatientInfo().getLastName(),
                appointment.getPatientInfo().getPhone(),
                appointment.getPatientInfo().getDocumentNumber(),
                appointment.getSpecialty(),
                appointment.getDate(),
                appointment.getStartTime().getTime().toString(),
                appointment.getAppointmentState(),
                appointment.getSchedulingOrigin()
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
