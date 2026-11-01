package co.edu.unicauca.piedrazul.backend.doctors.config;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.shared.seed.DemoSeedOnly;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Médicos de demostración (datos ficticios) para desarrollo. Solo existe con
 * {@link DemoSeedOnly}: perfil {@code dev} exclusivo y {@code APP_DEMO_SEED_ENABLED=true}.
 */
@Component
@DemoSeedOnly
@Order(2)
public class DoctorDataInitializer implements ApplicationRunner {

    private final DoctorRepository doctorRepository;
    private final UserProvisioningApi userProvisioningApi;

    public DoctorDataInitializer(DoctorRepository doctorRepository, UserProvisioningApi userProvisioningApi) {
        this.doctorRepository = doctorRepository;
        this.userProvisioningApi = userProvisioningApi;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (doctorRepository.count() > 0) return;

        //
        userProvisioningApi.createUser(new CreateSystemUserPayload(
                        new CreateSystemUserRequest(
                                "11000001",
                                 IdentificationType.CEDULA,
                                "Aurora",
                                "Demo Neural",
                                "aurora.neural@demo.piedrazul.test",
                                "3000000101",
                                "Doctor123!"
                        ), new CreateDoctorRequest(
                        List.of(SpecialtyCode.TERAPIA_NEURAL),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        20,
                        8,
                        List.of(
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.LUNES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MARTES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.JUEVES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.VIERNES)
                        )
                ),
                        null,
                        List.of(Role.DOCTOR, Role.SCHEDULER, Role.ADMIN))
        );


        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "11000002",
                         IdentificationType.CEDULA,
                        "Bruno",
                        "Demo Fisioterapia",
                        "bruno.fisio@demo.piedrazul.test",
                        "3000000102",
                        "Doctor123!"
                ),
                new CreateDoctorRequest(
                        List.of(SpecialtyCode.FISIOTERAPIA),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        30,
                        4,
                        List.of(
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.LUNES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.MARTES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(10, 0, 0), Workday.VIERNES)
                        )
                ),
                null,
                List.of(Role.DOCTOR)
        ));

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "11000003",
                         IdentificationType.CEDULA,
                        "Celia",
                        "Demo Quiropraxia",
                        "celia.quiro@demo.piedrazul.test",
                        "3000000103",
                        "Doctor123!"
                ),
                new CreateDoctorRequest(
                        List.of(SpecialtyCode.QUIROPRAXIA),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 3,1),
                        30,
                        8,
                        List.of(
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.LUNES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MARTES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.JUEVES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.VIERNES)
                        )
                ),
                null,
                List.of(Role.DOCTOR, Role.SCHEDULER, Role.ADMIN)
        ));

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "11000004",
                         IdentificationType.CEDULA,
                        "Dalia",
                        "Demo Neural",
                        "dalia.neural@demo.piedrazul.test",
                        "3000000104",
                        "Doctor123!"
                ),
                new CreateDoctorRequest(
                        List.of(SpecialtyCode.TERAPIA_NEURAL),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        10,
                        4,
                        List.of(
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.JUEVES),
                                new ScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.VIERNES)
                        )
                ),
                null,
                List.of(Role.DOCTOR)
        ));

        System.out.println("✔ Médicos de prueba insertados");
    }
}