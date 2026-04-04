package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@Order(2)
public class PatientDataInitializer implements ApplicationRunner {

    private final PatientRepository patientRepository;
    private final PatientService patientService;

    public PatientDataInitializer(
            PatientRepository patientRepository,
            PatientService patientService
    ) {
        this.patientRepository = patientRepository;
        this.patientService = patientService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (patientRepository.count() > 0) {
            return;
        }

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "12345678",
                "María",
                "López",
                "3001234567",
                "maria.lopez@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1990, 5, 15),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "87654321",
                "Carlos",
                "Martínez",
                "3109876543",
                "carlos.martinez@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1985, 8, 22),
                null
        );

        patientService.createPatient(
                PatientDocumentType.TARJETA_IDENTIDAD,
                "11122233",
                "Juan",
                "Pérez",
                "3201112233",
                null,
                PatientGender.MASCULINO,
                LocalDate.of(2012, 3, 10),
                "3204445566"
        );

        patientService.createPatientWithUser(
                "20202020202",
                "Patient123!",
                PatientDocumentType.CEDULA,
                "20202020202",
                "Juan",
                "Ortega",
                "3001234567",
                "jhon@de.com",
                PatientGender.MASCULINO,
                LocalDate.of(1995, 1, 1),
                null
        );

        System.out.println("✔ Pacientes de prueba insertados");
    }
}