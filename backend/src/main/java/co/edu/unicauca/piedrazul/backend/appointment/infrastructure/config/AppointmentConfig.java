package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.appointment.application.*;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentConfigJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentConfigRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.ListMyAppointmentsUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.application.CancelAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
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

    @Bean
    public AppointmentConfigRepository appointmentConfigRepository(
            AppointmentConfigJpaRepository jpaRepository) {
        return new AppointmentConfigRepositoryImpl(jpaRepository);
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


    @Bean
    public AppointmentSchedulingService appointmentSchedulingService(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            ApplicationEventPublisher eventPublisher,
            IsNewPatientUseCase isNewPatientUseCase,
            SecurityContextExtractor securityExtractor,
            AppointmentConfigRepository appointmentConfigRepository) {
        return new AppointmentSchedulingService(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService,
                eventPublisher,
                isNewPatientUseCase,
                securityExtractor,
                appointmentConfigRepository
        );
    }

    @Bean
    public ManualPatientResolutionStrategy manualPatientResolutionStrategy(PatientProvisioningPort patientProvisioningPort) {
        return new ManualPatientResolutionStrategy(patientProvisioningPort);
    }

    @Bean
    public AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy(PatientConsultPort patientConsultPort) {
        return new AutonomousPatientResolutionStrategy(patientConsultPort);
    }

    // --- CASOS DE USO (PUERTOS DE ENTRADA) ---

    @Bean
    public ListAppointmentsUseCase listAppointmentsUseCase(
            AppointmentRepository appointmentRepository) {
        return new ListAppointmentsUseCaseImpl(appointmentRepository);
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
            SlotTimeService slotTimeService,
            IsNewPatientUseCase isNewPatientUseCase) {
        return new GetSpecialtiesWithDoctorUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                slotTimeService,
                isNewPatientUseCase
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
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            ClinicalHistoryPort clinicalHistoryPort) {
        return new UpdateAppointmentStatusUseCaseImpl(appointmentRepository, doctorConfigConsultPort, clinicalHistoryPort);
    }

    @Bean
    public CancelAppointmentUseCase cancelAppointmentUseCase(
            AppointmentRepository appointmentRepository) {
        return new CancelAppointmentUseCaseImpl(appointmentRepository);
    }

    @Bean
    public UpdateExpiredAppointmentsUseCaseImpl  updateExpiredAppointmentsUseCase(
            AppointmentRepository appointmentRepository){
        return new UpdateExpiredAppointmentsUseCaseImpl(appointmentRepository);
    }

    @Bean
    public GetAppointmentStatesUseCase getAppointmentStatesUseCase() {
        return new GetAppointmentStatesUseCaseImpl();
    }

    @Bean
    public UpdateAutonomousSchedulingUseCase updateAutonomousSchedulingUseCase(AppointmentConfigRepository appointmentConfigRepository) {
        return new UpdateAutonomousSchedulingUseCaseImpl(appointmentConfigRepository);
    }

    @Bean
    public GetAutonomousSchedulingContidionUseCase getAutonomousSchedulingContidionUseCase(AppointmentConfigRepository appointmentConfigRepository) {
        return new GetAutonomousSchedulingContidionUseCaseImpl(appointmentConfigRepository);
    }

    @Bean
    public RegisterUnscheduledAttentionUseCase registerUnscheduledAttentionUseCase(
            DoctorConfigConsultPort doctorConfigConsultPort,
            ClinicalHistoryPort clinicalHistoryPort,
            ManualPatientResolutionStrategy manualPatientResolutionStrategy,
            AppointmentRepository appointmentRepository
    ){
        return new RegisterUnscheduledAttentionUseCaseImpl(
                doctorConfigConsultPort,
                clinicalHistoryPort,
                manualPatientResolutionStrategy,
                appointmentRepository
        );
    }

}
