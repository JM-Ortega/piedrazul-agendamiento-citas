package co.edu.unicauca.piedrazul.backend.report.integration;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class AppointmentDataClient {

    private final AppointmentExternalService appointmentExternalService;

    public AppointmentDataClient(AppointmentExternalService appointmentExternalService) {
        this.appointmentExternalService = appointmentExternalService;
    }

    public List<AppointmentSummary> getAppointmentForDoctorsToday(UUID doctorId) {
        return appointmentExternalService.findByDoctorAndDate(doctorId, LocalDate.now());
    }
}
