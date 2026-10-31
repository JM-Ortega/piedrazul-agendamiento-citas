package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.seed.DemoSeedOnly;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Pacientes de demostración (datos ficticios) para desarrollo. Solo existe con
 * {@link DemoSeedOnly}: perfil {@code dev} exclusivo y {@code APP_DEMO_SEED_ENABLED=true}.
 */
@Component
@DemoSeedOnly
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
                "Alan",
                "Demo",
                "3000000201",
                null,
                null,
                PatientSex.MASCULINO,
                LocalDate.of(2000, 3, 10),
                "3000000299"
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000004",
                "Bruna",
                "Demo",
                "3000000202",
                "bruna.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1985, 8, 22),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000005",
                "Carla",
                "Demo",
                "3000000203",
                "carla.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1990, 5, 15),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000006",
                "Dario",
                "Demo",
                "3000000204",
                "dario.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1992, 3, 10),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000007",
                "Elena",
                "Demo",
                "3000000205",
                "elena.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1988, 11, 5),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000008",
                "Fabio",
                "Demo",
                "3000000206",
                "fabio.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1995, 7, 18),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000009",
                "Gala",
                "Demo",
                "3000000207",
                "gala.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(2000, 1, 27),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000010",
                "Hugo",
                "Demo",
                "3000000208",
                "hugo.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1983, 9, 14),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000011",
                "Inés",
                "Demo",
                "3000000209",
                "ines.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1991, 4, 12),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000012",
                "Julio",
                "Demo",
                "3000000210",
                "julio.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1987, 6, 25),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000013",
                "Karen",
                "Demo",
                "3000000211",
                "karen.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1998, 2, 8),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000014",
                "Leo",
                "Demo",
                "3000000212",
                "leo.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1993, 10, 30),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000015",
                "Mara",
                "Demo",
                "3000000213",
                "mara.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1989, 12, 17),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000016",
                "Nico",
                "Demo",
                "3000000214",
                "nico.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1996, 8, 21),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000017",
                "Olga",
                "Demo",
                "3000000215",
                "olga.demo@demo.piedrazul.test",
                null,
                PatientSex.FEMENINO,
                LocalDate.of(1994, 6, 9),
                null
        );

        patientService.createPatient(
                IdentificationType.CEDULA,
                "33000018",
                "Pedro",
                "Demo",
                "3000000216",
                "pedro.demo@demo.piedrazul.test",
                null,
                PatientSex.MASCULINO,
                LocalDate.of(1986, 11, 28),
                null
        );

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "33000002",
                        IdentificationType.CEDULA,
                        "Pablo",
                        "Demo",
                        "pablo.demo@demo.piedrazul.test",
                        "3000000217",
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
