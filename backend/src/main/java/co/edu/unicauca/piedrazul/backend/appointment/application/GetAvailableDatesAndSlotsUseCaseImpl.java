package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableDatesAndSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingSchedule;

import java.time.LocalDate;
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
        this.appointmentRepository   = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.slotTimeService = slotTimeService;
    }

    // Obtener franjas disponibles para mostrarle al frontend antes de agendar
    @Override
    public List<AvailableDateSlots> getAvailableDatesAndSlots(UUID idDoctor) {

        // Slots en los que el doctor trabaja.
        // Este método ya tiene en cuenta:
        // - laborStart
        // - laborEnd
        // - bookingWindowWeeks
        // - días laborales
        // - festivos
        // - intervalo de atención
        WorkingSchedule workingDateSlots = doctorConfigConsultPort.workingSchedule(idDoctor);

        if (workingDateSlots.datesAndSlots().isEmpty()) {
            return List.of();
        }

        // Obtener el rango de fechas que devuelve el módulo de doctores
        LocalDate startDate = workingDateSlots.datesAndSlots().getFirst().date();
        LocalDate endDate = workingDateSlots.datesAndSlots().getLast().date();

        // Obtener todas las citas existentes del doctor en ese rango
        List<Appointment> existingAppointments =
                appointmentRepository.findByDoctorAndDateBetween(
                        idDoctor,
                        startDate,
                        endDate
                );

        // Quitar los horarios que ya están ocupados
        return slotTimeService.calculateAvailable(
                workingDateSlots.datesAndSlots(),
                existingAppointments,
                workingDateSlots.appointmentInterval()
        );
    }
}
