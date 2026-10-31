package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import co.edu.unicauca.piedrazul.backend.doctors.application.TimeOffService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorTimeOffRepository;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.SpecialtyRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Gracias config los servicios son clases normales sin Spring Java puro
 * Lo cual nos garantiza bajo acoplamiento
 */
@Configuration
public class DoctorModuleConfig {

    /**
     * Bean para ScheduleService
     */
    @Bean
    public ScheduleService scheduleService(DoctorRepository doctorRepository) {
        return new ScheduleService(doctorRepository);
    }

    /**
     * Bean para TimeOffService
     */
    @Bean
    public TimeOffService timeOffService(
            DoctorRepository doctorRepository,
            DoctorTimeOffRepository timeOffRepository,
            AppointmentExternalService appointmentExternalService,
            ApplicationEventPublisher eventPublisher,
            SecurityContextExtractor securityExtractor
    ) {
        return new TimeOffService(doctorRepository, timeOffRepository, appointmentExternalService,
                eventPublisher, securityExtractor);
    }

    /**
     * Bean para DoctorService
     */
    @Bean
    public DoctorService doctorService(
            DoctorRepository doctorRepository,
            AppointmentExternalService appointmentExternalService,
            PersonExternalService personExternalService,
            SpecialtyRepository specialtyRepository
    ) {
        return new DoctorService(doctorRepository, appointmentExternalService, personExternalService,
                specialtyRepository);
    }

    /**
     * Bean para DoctorExternalServiceImpl
     */
    @Bean
    public HolidayManager holidayManager() {
        return HolidayManager.getInstance(
                ManagerParameters.create("co")
        );
    }

    @Bean
    public DoctorExternalServiceImpl doctorExternalServiceImpl(
            DoctorRepository doctorRepository,
            ScheduleService scheduleService,
            PersonExternalService personExternalService,
            HolidayManager holidayManager,
            DoctorTimeOffRepository timeOffRepository
    ) {
        return new DoctorExternalServiceImpl(
                doctorRepository,
                scheduleService,
                personExternalService,
                holidayManager,
                timeOffRepository
        );
    }
}

