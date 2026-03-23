package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@Order(1)
public class DoctorDataInitializer implements ApplicationRunner {

    private final DoctorRepository doctorRepository;

    public DoctorDataInitializer(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (doctorRepository.count() > 0) return;

        Doctor doctor1 = new Doctor();
        doctor1.setIdUser(UUID.randomUUID());
        doctor1.setFirstName("Clara Inés");
        doctor1.setLastName("Córdoba");
        doctor1.setIdentification("DOC-001");
        doctor1.setSpecialty(List.of(Specialty.TERAPIA_NEURAL));
        doctor1.setStatus(true);
        doctor1.setLaborEnd(LocalDate.of(2026, 12, 31));
        doctor1.setAppointmentInterval(5);

        Doctor doctor2 = new Doctor();
        doctor2.setIdUser(UUID.randomUUID());
        doctor2.setFirstName("José Ignacio");
        doctor2.setLastName("García");
        doctor2.setIdentification("DOC-002");
        doctor2.setSpecialty(List.of(Specialty.FISIOTERAPIA));
        doctor2.setStatus(true);
        doctor2.setLaborEnd(LocalDate.of(2026, 12, 31));
        doctor2.setAppointmentInterval(10);

        Doctor doctor3 = new Doctor();
        doctor3.setIdUser(UUID.randomUUID());
        doctor3.setFirstName("Armando");
        doctor3.setLastName("Peña");
        doctor3.setIdentification("DOC-003");
        doctor3.setSpecialty(List.of(Specialty.QUIROPRAXIA));
        doctor3.setStatus(true);
        doctor3.setLaborEnd(LocalDate.of(2026, 12, 31));
        doctor3.setAppointmentInterval(15);

        doctorRepository.saveAll(List.of(doctor1, doctor2, doctor3));

        System.out.println("✔ Médicos de prueba insertados");
    }
}