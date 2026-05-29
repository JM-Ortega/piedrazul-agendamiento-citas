package co.edu.unicauca.piedrazul.backend.report.integration;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.report.dtos.AppointmentStateFilter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
@Component
public class AppointmentDataClient {

    private final AppointmentExternalService appointmentExternalService;

    public AppointmentDataClient(AppointmentExternalService appointmentExternalService) {
        this.appointmentExternalService = appointmentExternalService;
    }

    public List<AppointmentSummary> getAppointmentForDoctorsToday(UUID doctorId, AppointmentStateFilter state) {
        // Si state es null, pasa null al servicio externo (traer todas)
        String stateName = state != null ? state.name() : null;
        return appointmentExternalService.findByDoctorAndDate(doctorId, LocalDate.now(), stateName);
    }

    public List<SchedulerAppointmentSummary> getAllAppointmentsByDate(LocalDate date){
        return appointmentExternalService.findAllByDate(date);
    }

    public boolean hasAvailableSlots(LocalDate date){
        return appointmentExternalService.hasAvailableSlots(date);
    }
}
