package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public DoctorService doctorService(DoctorRepository doctorRepository, UserModuleApi userModuleApi) {
        return new DoctorService(doctorRepository, userModuleApi);
    }
}

