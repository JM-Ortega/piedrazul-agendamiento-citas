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
                PatientDocumentType.TARJETA_IDENTIDAD,
                "33000003",
                "Juan",
                "Pérez",
                "3201112233",
                null,
                PatientGender.MASCULINO,
                LocalDate.of(2012, 3, 10),
                "3204445566"
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000004",
                "Sofia",
                "Char",
                "3109876543",
                "sofia.char@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1985, 8, 22),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000005",
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
                "33000006",
                "Carlos",
                "Ramírez",
                "3201112233",
                "carlos.ramirez@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1992, 3, 10),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000007",
                "Laura",
                "Gómez",
                "3212223344",
                "laura.gomez@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1988, 11, 5),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000008",
                "Andrés",
                "Martínez",
                "3223334455",
                "andres.martinez@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1995, 7, 18),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000009",
                "Valentina",
                "Torres",
                "3234445566",
                "valentina.torres@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(2000, 1, 27),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000010",
                "Juan",
                "Herrera",
                "3245556677",
                "juan.herrera@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1983, 9, 14),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000011",
                "Natalia",
                "Castro",
                "3256667788",
                "natalia.castro@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1991, 4, 12),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000012",
                "Diego",
                "Morales",
                "3267778899",
                "diego.morales@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1987, 6, 25),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000013",
                "Camila",
                "Rojas",
                "3278889900",
                "camila.rojas@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1998, 2, 8),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000014",
                "Felipe",
                "Vargas",
                "3289990011",
                "felipe.vargas@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1993, 10, 30),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000015",
                "Daniela",
                "Mendoza",
                "3290001122",
                "daniela.mendoza@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1989, 12, 17),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000016",
                "Sebastián",
                "Ortega",
                "3301112233",
                "sebastian.ortega@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1996, 8, 21),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000017",
                "Paula",
                "Jiménez",
                "3312223344",
                "paula.jimenez@email.com",
                PatientGender.FEMENINO,
                LocalDate.of(1994, 6, 9),
                null
        );

        patientService.createPatient(
                PatientDocumentType.CEDULA,
                "33000018",
                "Miguel",
                "Salazar",
                "3323334455",
                "miguel.salazar@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1986, 11, 28),
                null
        );

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "33000002",
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