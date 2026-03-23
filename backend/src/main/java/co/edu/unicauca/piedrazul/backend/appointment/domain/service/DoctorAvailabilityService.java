package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

public class DoctorAvailabilityService {
    private final BusySlotService busySlotService;

    public DoctorAvailabilityService(BusySlotService busySlotService) {
        this.busySlotService = busySlotService;
    }

    // ¿Tiene este médico al menos un slot libre desde hoy?
    public boolean hasAvailabilityFromToday(DoctorResponse doctor,
                                            List<AppointmentTime> slots,
                                            List<Appointment> existingAppointments,
                                            int intervalMinutes) {
        // Verifica que la fecha final de trabajo no haya pasado
        if (doctor.laborEnd() != null
                && doctor.laborEnd().isBefore(LocalDate.now())) {
            return false;
        }

        // Verifica que trabaje al menos hoy o próximamente
        LocalDate today = LocalDate.now();
        boolean trabajaProximamente = doctor.workdays().stream()
                .anyMatch(day -> {
                    DayOfWeek dayOfWeek = DayOfWeek.of(day);
                    LocalDate nextWorkDay = today.with(
                            TemporalAdjusters.nextOrSame(dayOfWeek)
                    );
                    return doctor.laborEnd() == null
                            || !doctor.laborEnd().isBefore(nextWorkDay);
                });

        if (!trabajaProximamente) return false;

        // Verifica que tenga al menos un slot libre hoy
        return slots.stream()
                .anyMatch(slot -> !busySlotService.isBusy(
                        existingAppointments, slot, intervalMinutes
                ));
    }
}
