package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.SpecialtyRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
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
    public DoctorExternalServiceImpl doctorExternalServiceImpl(
            DoctorRepository doctorRepository,
            ScheduleService scheduleService,
            PersonExternalService personExternalService,
            DoctorService doctorService
    ) {
        return new DoctorExternalServiceImpl(doctorRepository, scheduleService, personExternalService, doctorService);
    }
}

