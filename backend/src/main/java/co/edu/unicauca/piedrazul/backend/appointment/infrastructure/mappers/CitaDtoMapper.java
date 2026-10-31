package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    // CitaDtoMapper — nuevo método para listas
    public List<AppointmentResponse> toResponseList(List<Appointment> appointments) {
        Set<UUID> patientIds = appointments.stream().map(Appointment::getIdPatient).collect(Collectors.toSet());
        Set<UUID> doctorIds = appointments.stream().map(Appointment::getIdDoctor).collect(Collectors.toSet());

        Map<UUID, PatientInfo> patientsById = patientConsultPort.findByIds(patientIds);
        Map<UUID, String> doctorNamesById = doctorConfigConsultPort.getDoctorInfoByIds(doctorIds.stream().toList())
                .stream().collect(Collectors.toMap(DoctorResponse::id, DoctorResponse::name));

        return appointments.stream()
                .map(a -> {
                    PatientInfo patient = patientsById.get(a.getIdPatient());
                    return new AppointmentResponse(
                            a.getIdAppointment(), a.getDate(), a.getStartTime().getTime(), a.getAppointmentState(),
                            doctorNamesById.get(a.getIdDoctor()), a.getSpecialty(),
                            patient.getFirstName(), patient.getLastName(), patient.getDocumentNumber()
                    );
                }).toList();
    }

}
