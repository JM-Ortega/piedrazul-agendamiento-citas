package co.edu.unicauca.piedrazul.backend.statistics.integration;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyTotalAppointmentDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyAppointmentsCountDto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class AppointmentStatsClient {

    private final AppointmentExternalService appointmentExternalService;

    public AppointmentStatsClient(AppointmentExternalService appointmentExternalService) {
        this.appointmentExternalService = appointmentExternalService;
    }

    public List<MonthlyTotalAppointmentDto> getAttendedByMonth(int year) {
        return appointmentExternalService.getAttendedAppointmentsCountByMonth(year);
    }

    public DoctorAppointmentsCountDto getCountByDoctor(int year, String state) {
        return appointmentExternalService.getAppointmentsCountByDoctor(year, state);
    }

    public SpecialtyAppointmentsCountDto getCountBySpecialty(int year, String state) {
        return appointmentExternalService.getAppointmentsCountBySpecialty(year, state);
    }

    public List<MonthlyCancellationDataDto> getCancellationData(int year, String state) {
        return appointmentExternalService.getCancellationData(year, state);
    }

    public List<DoctorDailyCountDto> getDailyCountByDoctor(LocalDate date) {
        return appointmentExternalService.getDoctorsAppointmentCountByDate(date);
    }
}
