package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.appointment.aplication.GetAvailableSlotsUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.aplication.ListAppointmentsUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.aplication.ScheduleAutonomousAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.aplication.ScheduleManualAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import org.springframework.context.annotation.Bean;

public class AppointmentConfig {

    // Servicios de dominio
    @Bean
    public BusySlotService busySlotService() {
        return new BusySlotService();
    }

    @Bean
    public SlotTimeService slotTimeService(BusySlotService busySlotService) {
        return new SlotTimeService(busySlotService);
    }

    @Bean
    public AppointmentService appointmentService(BusySlotService busySlotService,
                                                 SlotTimeService slotTimeService) {
        return new AppointmentService(busySlotService, slotTimeService);
    }

    // Casos de uso
    @Bean
    public ListAppointmentsUseCase listAppointmentsUseCase(
            AppointmentRepository appointmentRepository) {
        return new ListAppointmentsUseCaseImpl(appointmentRepository);
    }

    @Bean
    public ScheduleManualAppointmentUseCase scheduleManualAppointmentUseCase(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        return new ScheduleManualAppointmentUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService
        );
    }

    @Bean
    public ScheduleAutonomousAppointmentUseCase scheduleAutonomousAppointmentUseCase(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        return new ScheduleAutonomousAppointmentUseCaseImpl(
                appointmentRepository,
                patientConsultPort,
                doctorConfigConsultPort,
                appointmentService
        );
    }

    @Bean
    public GetAvailableSlotsUseCase getAvailableSlotsUseCase(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        return new GetAvailableSlotsUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService
        );
    }
}
