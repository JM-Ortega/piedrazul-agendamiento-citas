package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@Order(2)
public class PatientDataInitializer implements ApplicationRunner {

    private final PatientRepository patientRepository;
    private final PatientService patientService;
        private final UserProvisioningApi userProvisioningApi;

    public PatientDataInitializer(
            PatientRepository patientRepository,
            PatientService patientService,
                        UserProvisioningApi userProvisioningApi
    ) {
        this.patientRepository = patientRepository;
        this.patientService = patientService;
                this.userProvisioningApi = userProvisioningApi;
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

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "2020202020",
                        "José",
                        "Paz",
                        "jose@gmail.com",
                        "Patient123!"
                ),null,
                new CreatePatientUserRequest(
                        PatientDocumentType.CEDULA,
                        "3001234567",
                        PatientGender.MASCULINO,
                        LocalDate.of(1995, 1, 1),
                        null
                ), List.of(Role.PATIENT)
        ));

        System.out.println("✔ Pacientes de prueba insertados");
    }
}