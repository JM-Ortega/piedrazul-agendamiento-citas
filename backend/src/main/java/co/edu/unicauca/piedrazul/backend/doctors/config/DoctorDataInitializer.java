package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
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
    private final UserProvisioningApi userProvisioningApi;

    public DoctorDataInitializer(UserProvisioningApi userProvisioningApi) {
        this.userProvisioningApi = userProvisioningApi;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedDoctorIfMissing(
                "11000000",
                "Clara Inés",
                "Córdoba",
                "clara.cordoba@piedrazul.dev",
                "Doctor123!",
                new CreateDoctorRequest(
                        DocumentType.CEDULA,
                        "3208337463",
                        List.of(Specialty.TERAPIA_NEURAL),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        30,
                        List.of(
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.LUNES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.MARTES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.JUEVES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.VIERNES)
                        )
                )
        );

        seedDoctorIfMissing(
                "12000000",
                "José Ignacio",
                "García",
                "jose.garcia@piedrazul.dev",
                "Doctor123!",
                new CreateDoctorRequest(
                        DocumentType.CEDULA,
                        "3147826393",
                        List.of(Specialty.FISIOTERAPIA),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        40,
                        List.of(
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.MARTES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(10, 0, 0), Workday.VIERNES)
                        )
                )
        );

        seedDoctorIfMissing(
                "13000000",
                "Armando",
                "Peña",
                "armando.pena@piedrazul.dev",
                "Doctor123!",
                new CreateDoctorRequest(
                        DocumentType.CEDULA,
                        "314738447",
                        List.of(Specialty.QUIROPRAXIA, Specialty.MEDICINA_GENERAL),
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 12, 31),
                        15,
                        Collections.emptyList()
                )
        );

        seedDoctorIfMissing(
                "14000000",
                "Rocío",
                "Gómez",
                "rocio.gomez@piedrazul.dev",
                "Doctor123!",
                new CreateDoctorRequest(
                        DocumentType.CEDULA,
                        "3147826393",
                        List.of(Specialty.MEDICINA_GENERAL),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        40,
                        List.of(
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.MARTES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(10, 0, 0), Workday.VIERNES)
                        )
                )
        );

        System.out.println("✔ Médicos de prueba insertados");
    }

    private void seedDoctorIfMissing(
            String identification,
            String firstName,
            String lastName,
            String email,
            String password,
            CreateDoctorRequest doctorRequest
    ) {
        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        identification,
                        firstName,
                        lastName,
                        email,
                        password
                ),
                doctorRequest,
                null,
                List.of(Role.DOCTOR)
        ));
    }
}