package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;

import java.util.List;
import java.util.stream.Collectors;

public class SlotTimeService {
    private final BusySlotService busySlotService;

    public SlotTimeService(BusySlotService busySlotService) {
        this.busySlotService = busySlotService;
    }

    // Las franjas del medico vienen del modulo de medicos
    // Las citas existentes del medico se sacan de la BD
    public List<AppointmentTime> calculateAvailable (List<AppointmentTime> doctorSlots,
                                                     List<Appointment> appointmentsOfDay,
                                                     int minutesInterval){
        return doctorSlots.stream()
                .filter(slot -> !busySlotService.isBusy(appointmentsOfDay, slot, minutesInterval))
                .collect(Collectors.toList());
    }
}
