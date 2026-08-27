package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@Order(3)
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
                IdentificationType.CEDULA,
                "33000003",
                "Juan",
                "Pérez",
                "3201112233",
                null,
                null,
                PatientSex.MASCULINO,
                LocalDate.of(2000, 3, 10),
                "3204445566"
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000004",
                "Sofia",
                "Char",
                "3109876543",
                "sofia.char@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1985, 8, 22),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000005",
                "María",
                "López",
                "3001234567",
                "maria.lopez@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1990, 5, 15),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000006",
                "Carlos",
                "Ramírez",
                "3201112233",
                "carlos.ramirez@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1992, 3, 10),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000007",
                "Laura",
                "Gómez",
                "3212223344",
                "laura.gomez@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1988, 11, 5),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000008",
                "Andrés",
                "Martínez",
                "3223334455",
                "andres.martinez@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1995, 7, 18),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000009",
                "Valentina",
                "Torres",
                "3234445566",
                "valentina.torres@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(2000, 1, 27),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000010",
                "Juan",
                "Herrera",
                "3245556677",
                "juan.herrera@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1983, 9, 14),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000011",
                "Natalia",
                "Castro",
                "3256667788",
                "natalia.castro@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1991, 4, 12),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000012",
                "Diego",
                "Morales",
                "3267778899",
                "diego.morales@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1987, 6, 25),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000013",
                "Camila",
                "Rojas",
                "3278889900",
                "camila.rojas@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1998, 2, 8),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000014",
                "Felipe",
                "Vargas",
                "3289990011",
                "felipe.vargas@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1993, 10, 30),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000015",
                "Daniela",
                "Mendoza",
                "3290001122",
                "daniela.mendoza@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1989, 12, 17),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000016",
                "Sebastián",
                "Ortega",
                "3301112233",
                "sebastian.ortega@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1996, 8, 21),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000017",
                "Paula",
                "Jiménez",
                "3312223344",
                "paula.jimenez@email.com",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1994, 6, 9),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000018",
                "Miguel",
                "Salazar",
                "3323334455",
                "miguel.salazar@email.com",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1986, 11, 28),
                null
        );

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "33000002",
                        IdentificationType.CEDULA,
                        "José",
                        "Paz",
                        "jose@gmail.com",
                        "3001234567",
                        "Patient123!"
                ), null,
                new CreatePatientUserRequest(
                        PatientSex.MASCULINO,
                        LocalDate.of(1995, 1, 1),
                        null
                ), List.of(Role.PATIENT)
        ));

        System.out.println("✔ Pacientes de prueba insertados");
    }
}
