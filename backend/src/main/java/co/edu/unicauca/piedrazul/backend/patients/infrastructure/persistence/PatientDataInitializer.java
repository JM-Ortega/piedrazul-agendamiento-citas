package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@Order(2)
public class PatientDataInitializer implements ApplicationRunner {

    private final PatientRepository patientRepository;

    public PatientDataInitializer(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (patientRepository.count() > 0) return;

        Patient patient1 = new Patient(
                DocumentType.CEDULA,
                "12345678",
                "María",
                "López",
                "3001234567",
                "maria.lopez@email.com",
                Gender.FEMENINO,
                LocalDate.of(1990, 5, 15),
                null,  // sin acudiente — mayor de edad
                null   // sin cuenta de usuario aún
        );

        Patient patient2 = new Patient(
                DocumentType.CEDULA,
                "87654321",
                "Carlos",
                "Martínez",
                "3109876543",
                "carlos.martinez@email.com",
                Gender.MASCULINO,
                LocalDate.of(1985, 8, 22),
                null,  // sin acudiente — mayor de edad
                null   // sin cuenta de usuario aún
        );

        // Menor de edad — tiene acudiente
        Patient patient3 = new Patient(
                DocumentType.TARJETA_IDENTIDAD,
                "11122233",
                "Juan",
                "Pérez",
                "3201112233",
                null,              // email opcional
                Gender.MASCULINO,
                LocalDate.of(2012, 3, 10),
                "3204445566",      // teléfono del acudiente
                null
        );

        patientRepository.saveAll(List.of(patient1, patient2, patient3));

        System.out.println("✔ Pacientes de prueba insertados");
    }
}
