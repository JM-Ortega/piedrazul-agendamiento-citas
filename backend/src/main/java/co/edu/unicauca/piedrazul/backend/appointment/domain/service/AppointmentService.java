package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;


import java.util.List;

public class AppointmentService {

    private final BusySlotService busySlotService;

    public AppointmentService(BusySlotService busySlotService) {
        this.busySlotService = busySlotService;
    }

    public Appointment scheduleManual(
            AppointmentSchedulingRequest request,
            int intervalMinutes,
            List<Appointment> existingAppointments) {

        validateSlot(
                existingAppointments,
                request.startTime(),
                intervalMinutes
        );

        return Appointment.scheduleManual(request);
    }

    public Appointment scheduleAutonomous(
            AppointmentSchedulingRequest request,
            int intervalMinutes,
            List<Appointment> existingAppointments) {
        validateSlot(
                existingAppointments,
                request.startTime(),
                intervalMinutes
        );

        return Appointment.scheduleAutonomous(request);
    }

    private void validateSlot(
            List<Appointment> existingAppointments,
            AppointmentTime startTime,
            int intervalMinutes) {

        if (busySlotService.isBusy(
                existingAppointments,
                startTime,
                intervalMinutes)) {

            throw new SlotNotAvailableException(
                    "El slot " + startTime + " ya está ocupado"
            );
        }
    }
}
