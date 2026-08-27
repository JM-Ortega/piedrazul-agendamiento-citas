package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
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
    public ScheduleService scheduleService(ScheduleRepository scheduleRepository) {
        return new ScheduleService(scheduleRepository);
    }

    /**
     * Bean para DoctorService
     */
    @Bean
    public DoctorService doctorService(
            DoctorRepository doctorRepository,
            UserModuleApi userModuleApi,
            AppointmentExternalService appointmentExternalService
    ) {
        return new DoctorService(doctorRepository, userModuleApi, appointmentExternalService);
    }

    /**
     * Bean para DoctorExternalServiceImpl
     */
    @Bean
    public DoctorExternalServiceImpl doctorExternalServiceImpl(
            DoctorRepository doctorRepository,
            ScheduleService scheduleService
    ) {
        return new DoctorExternalServiceImpl(doctorRepository, scheduleService);
    }
}

