package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.appointment.application.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.ListMyAppointmentsUseCaseImpl;
import org.springframework.context.ApplicationEventPublisher;
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
    public AppointmentService appointmentService(BusySlotService busySlotService) {
        return new AppointmentService(busySlotService);
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
            PatientConsultPort patientConsultPort,
            ApplicationEventPublisher eventPublisher) {
        return new ScheduleManualAppointmentUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService,
                patientConsultPort,
                eventPublisher
        );
    }

    @Bean
    public ScheduleAutonomousAppointmentUseCase scheduleAutonomousAppointmentUseCase(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            ApplicationEventPublisher eventPublisher) {
        return new ScheduleAutonomousAppointmentUseCaseImpl(
                appointmentRepository,
                patientConsultPort,
                doctorConfigConsultPort,
                appointmentService,
                eventPublisher
        );
    }

    @Bean
    public GetAvailableSlotsUseCase getAvailableSlotsUseCase(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            SlotTimeService slotTimeService) {
        return new GetAvailableSlotsUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                slotTimeService
        );
    }

    @Bean
    public GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            SlotTimeService slotTimeService) {
        return new GetSpecialtiesWithDoctorUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                slotTimeService
        );
    }

    @Bean
    public ListMyAppointmentsUseCase listMyAppointmentsUseCase(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort) {
        return new ListMyAppointmentsUseCaseImpl(
                appointmentRepository,
                patientConsultPort
        );
    }

    @Bean
    public IsNewPatientUseCase isNewPatientUseCase(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort) {
        return new IsNewPatientUseCaseImpl(appointmentRepository, patientConsultPort);
    }

    @Bean
    public UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase(
            AppointmentRepository appointmentRepository) {
        return new UpdateAppointmentStatusUseCaseImpl(appointmentRepository);
    }
}
