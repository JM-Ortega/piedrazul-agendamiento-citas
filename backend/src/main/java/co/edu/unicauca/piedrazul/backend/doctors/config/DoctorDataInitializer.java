package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

@Component
@Order(1)
public class DoctorDataInitializer implements ApplicationRunner {

    private final DoctorRepository doctorRepository;
    private final DoctorService doctorService;

    public DoctorDataInitializer(DoctorRepository doctorRepository, DoctorService doctorService) {
        this.doctorRepository = doctorRepository;
        this.doctorService = doctorService;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (doctorRepository.count() > 0) return;

        doctorService.createDoctor(new CreateDoctorRequest(
                "Clara Inés",
                "Córdoba",
                "11000000",
                "3208337463",
                List.of(Specialty.TERAPIA_NEURAL),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                30,
                List.of(new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.LUNES),
                        new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.MARTES),
                        new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.JUEVES),
                        new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.VIERNES)),
                "clara.cordoba@piedrazul.dev",
                "Doctor123!"
        ));

        doctorService.createDoctor(new CreateDoctorRequest(
                "José Ignacio",
                "García",
                "12000000",
                "3147826393",
                List.of(Specialty.FISIOTERAPIA),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                40,
                List.of(new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.MARTES),
                        new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                        new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(10, 0, 0), Workday.VIERNES)),
                "jose.garcia@piedrazul.dev",
                "Doctor123!"
        ));

        doctorService.createDoctor(new CreateDoctorRequest(
                "Armando",
                "Peña",
                "13000000",
                "314738447",
                List.of(Specialty.QUIROPRAXIA),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 12, 31),
                15,
                Collections.emptyList(),
                "armando.pena@piedrazul.dev",
                "Doctor123!"
        ));

        System.out.println("✔ Médicos de prueba insertados");
    }
}