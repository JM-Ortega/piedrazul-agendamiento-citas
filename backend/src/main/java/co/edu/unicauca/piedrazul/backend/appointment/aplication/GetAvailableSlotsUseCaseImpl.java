package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;


public class GetAvailableSlotsUseCaseImpl implements GetAvailableSlotsUseCase {
    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;

    public GetAvailableSlotsUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        this.appointmentRepository   = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService      = appointmentService;
    }

    // Obtener franjas disponibles para mostrarle al frontend antes de agendar
    @Override
    public List<AppointmentTime> getAvailableSlots(UUID idDoctor, LocalDate date) {
        // 1. Obtiene las franjas del médico para ese día desde el módulo de médicos
        List<AppointmentTime> doctorSlots = doctorConfigConsultPort
                .getSlotsByDoctor(idDoctor, date);

        // 2. Obtiene las citas ya agendadas ese día
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndDate(idDoctor, date);

        // 3. Obtiene el intervalo configurado para el médico
        int intervalMinutes = doctorConfigConsultPort
                .getIntervalMinutesByDoctor(idDoctor);

        // 4. Delega al servicio de dominio el filtrado de slots disponibles
        return appointmentService.getAvailableSlots(
                doctorSlots, existingAppointments, intervalMinutes
        );
    }
}
