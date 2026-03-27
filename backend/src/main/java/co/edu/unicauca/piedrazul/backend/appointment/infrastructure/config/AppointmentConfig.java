package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.appointment.aplication.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetSpecialtiesWithDoctorUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppointmentConfig {

    // --- ADAPTADORES DE INFRAESTRUCTURA (LOS PUERTOS DE SALIDA) ---

    @Bean
    public AppointmentRepository appointmentRepository(
            AppointmentJpaRepository jpaRepository,
            AppointmentMapper mapper) {
        return new AppointmentRepositoryImpl(jpaRepository, mapper);
    }

    // --- SERVICIOS DE DOMINIO (LÓGICA PURA) ---

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

    // --- CASOS DE USO (PUERTOS DE ENTRADA) ---

    @Bean
    public ListAppointmentsUseCase listAppointmentsUseCase(
            AppointmentRepository appointmentRepository) {
        return new ListAppointmentsUseCaseImpl(appointmentRepository);
    }

    @Bean
    public ScheduleManualAppointmentUseCase scheduleManualAppointmentUseCase(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            PatientConsultPort patientConsultPort) {
        return new ScheduleManualAppointmentUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService,
                patientConsultPort
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

    @Bean
    public GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort) {
        return new GetSpecialtiesWithDoctorUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort
        );
    }
}
