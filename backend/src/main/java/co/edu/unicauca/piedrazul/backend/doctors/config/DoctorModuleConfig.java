package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.ScheduleRepository;
import co.edu.unicauca.piedrazul.backend.doctors.model.services.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.model.services.ScheduleService;
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
    public DoctorService doctorService(DoctorRepository doctorRepository) {
        return new DoctorService(doctorRepository);
    }
}

