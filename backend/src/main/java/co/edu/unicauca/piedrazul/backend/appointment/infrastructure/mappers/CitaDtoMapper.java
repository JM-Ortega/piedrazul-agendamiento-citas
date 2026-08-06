package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import org.springframework.stereotype.Component;

@Component
public class CitaDtoMapper {
    private final PatientConsultPort patientConsultPort;
    private final DoctorConfigConsultPort doctorConfigConsultPort;


    public CitaDtoMapper(PatientConsultPort patientConsultPort, DoctorConfigConsultPort doctorConfigConsultPort) {
        this.patientConsultPort = patientConsultPort;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
    }

    // Appointment -> AppointmentResponse
    public AppointmentResponse toResponse(Appointment appointment) {
        PatientInfo patientInfo = patientConsultPort.findById(appointment.getIdPatient());
        String doctorName = doctorConfigConsultPort.getDoctorName(appointment.getIdDoctor());


        return new AppointmentResponse(
                appointment.getIdAppointment(),
                appointment.getDate(),
                appointment.getStartTime().getTime(),
                appointment.getAppointmentState(),
                doctorName,
                appointment.getSpecialty(),
                patientInfo.getFirstName(),
                patientInfo.getLastName(),
                patientInfo.getDocumentNumber()
        );
    }

}
