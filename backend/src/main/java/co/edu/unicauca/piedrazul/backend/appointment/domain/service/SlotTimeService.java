package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SlotTimeService {

    private final BusySlotService busySlotService;

    public SlotTimeService(BusySlotService busySlotService) {
        this.busySlotService = busySlotService;
    }

    public List<AvailableDateSlots> calculateAvailable(
            List<WorkingDateSlots> workingDatesAndSlots,
            List<Appointment> existingAppointments,
            int minutesInterval
    ) {

        Map<LocalDate, List<Appointment>> appointmentsByDate =
                existingAppointments.stream()
                        .collect(Collectors.groupingBy(
                                Appointment::getDate
                        ));

        return workingDatesAndSlots.stream()
                .map(workingDateSlots -> {

                    List<Appointment> appointmentsOfDay =
                            appointmentsByDate.getOrDefault(
                                    workingDateSlots.date(),
                                    List.of()
                            );

                    List<LocalTime> availableSlots =
                            workingDateSlots.slots().stream()
                                    .filter(slot ->
                                            isAvailable(
                                                    slot,
                                                    appointmentsOfDay,
                                                    minutesInterval
                                            )
                                    )
                                    .toList();

                    return new AvailableDateSlots(
                            workingDateSlots.date(),
                            availableSlots
                    );
                })
                .filter(dateSlots ->
                        !dateSlots.availableSlots().isEmpty()
                )
                .toList();
    }

    private boolean isAvailable(
            LocalTime slot,
            List<Appointment> appointmentsOfDay,
            int minutesInterval
    ) {
        AppointmentTime appointmentTime =
                AppointmentTime.withoutBusinessHoursRestriction(slot);

        return !busySlotService.isBusy(
                appointmentsOfDay,
                appointmentTime,
                minutesInterval
        );
    }
}
