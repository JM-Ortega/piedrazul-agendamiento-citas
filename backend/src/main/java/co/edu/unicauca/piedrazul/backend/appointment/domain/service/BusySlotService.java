package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;

import java.time.LocalTime;
import java.util.List;

public class BusySlotService {
    // Devuelve true si esa hora ya esta ocupada por una cita
    public boolean isBusy(List<Appointment> existingAppointments, AppointmentTime newHour, int minutesInterval){
        return  existingAppointments.stream()
                .filter(c -> c.getAppointmentState().isBussy())
                .anyMatch(c -> c.getStartTime()
                                            .collidesWith(newHour, minutesInterval));
    }
}
