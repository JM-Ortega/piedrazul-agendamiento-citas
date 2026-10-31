package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableDatesAndSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingSchedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public class GetAvailableDatesAndSlotsUseCaseImpl implements GetAvailableDatesAndSlotsUseCase {
    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final SlotTimeService slotTimeService;

    public GetAvailableDatesAndSlotsUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            SlotTimeService slotTimeService) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.slotTimeService = slotTimeService;
    }

    @Override
    public List<LocalDate> getAvailableDates(UUID idDoctor) {
        return getAvailableDateSlots(idDoctor).stream()
                .map(AvailableDateSlots::date)
                .toList();
    }

    public List<AvailableDateSlots> getAvailableDatesAndSlots(UUID idDoctor) {
        return getAvailableDateSlots(idDoctor);
    }

    @Override
    public List<LocalTime> getAvailableSlots(UUID idDoctor, LocalDate date) {
        WorkingSchedule workingSchedule = doctorConfigConsultPort.workingSchedule(idDoctor);
        WorkingDateSlots workingDateSlots = workingSchedule.datesAndSlots().stream()
                .filter(dateSlots -> dateSlots.date().equals(date))
                .findFirst()
                .orElse(null);

        if (workingDateSlots == null) {
            return List.of();
        }

        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorAndDateBetween(idDoctor, date, date);

        return slotTimeService.calculateAvailable(
                List.of(workingDateSlots),
                existingAppointments,
                workingSchedule.appointmentInterval())
                .stream()
                .flatMap(dateSlots -> dateSlots.availableSlots().stream())
                .toList();
    }

    private List<AvailableDateSlots> getAvailableDateSlots(UUID idDoctor) {

        WorkingSchedule workingDateSlots = doctorConfigConsultPort.workingSchedule(idDoctor);

        if (workingDateSlots.datesAndSlots().isEmpty()) {
            return List.of();
        }

        // Obtener el rango de fechas que devuelve el módulo de doctores
        LocalDate startDate = workingDateSlots.datesAndSlots().getFirst().date();
        LocalDate endDate = workingDateSlots.datesAndSlots().getLast().date();

        // Obtener todas las citas existentes del doctor en ese rango
        List<Appointment> existingAppointments = appointmentRepository.findByDoctorAndDateBetween(
                idDoctor,
                startDate,
                endDate);

        // Quitar los horarios que ya están ocupados
        return slotTimeService.calculateAvailable(
                workingDateSlots.datesAndSlots(),
                existingAppointments,
                workingDateSlots.appointmentInterval());
    }
}
