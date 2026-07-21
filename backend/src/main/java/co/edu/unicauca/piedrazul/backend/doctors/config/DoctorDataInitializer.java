package co.edu.unicauca.piedrazul.backend.doctors.config;
/*
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.user.UserProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Component
@Order(1)
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
                                "Clara Inés",
                                "Córdoba",
                                "clara.cordoba@piedrazul.com",
                                "3208337463",
                                "Doctor123!"
                        ), new CreateDoctorRequest(
                        List.of(SpecialtyCode.TERAPIA_NEURAL),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        20,
                        8,
                        List.of(new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.LUNES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.MARTES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.JUEVES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.VIERNES))
                ),
                        null,
                        List.of(Role.DOCTOR))
        );


        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "11000002",
                         IdentificationType.CEDULA,
                        "José Ignacio",
                        "García",
                        "jose.garcia@piedrazul.com",
                        "3147826393",
                        "Doctor123!"
                ),
                new CreateDoctorRequest(
                        List.of(SpecialtyCode.FISIOTERAPIA),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        30,
                        4,
                        List.of(
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.LUNES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(9, 0, 0), Workday.MARTES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(10, 0, 0), Workday.VIERNES)
                        )
                ),
                null,
                List.of(Role.DOCTOR)
        ));

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "11000003",
                         IdentificationType.CEDULA,
                        "Ibis Ester",
                        "Peña",
                        "ibis.pena@piedrazul.com",
                        "314738447",
                        "Doctor123!"
                ),
                new CreateDoctorRequest(
                        List.of(SpecialtyCode.QUIROPRAXIA),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 3,1),
                        30,
                        8,
                        List.of(
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(11, 0, 0), Workday.JUEVES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(10, 0, 0), Workday.VIERNES)
                        )
                ),
                null,
                List.of(Role.DOCTOR)
        ));

        userProvisioningApi.createUser(new CreateSystemUserPayload(
                new CreateSystemUserRequest(
                        "11000004",
                         IdentificationType.CEDULA,
                        "Rocío",
                        "Gómez",
                        "rocio.gomez@piedrazul.dev",
                        "3147826393",
                        "Doctor123!"
                ),
                new CreateDoctorRequest(
                        List.of(SpecialtyCode.MEDICINA_GENERAL),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        10,
                        4,
                        List.of(
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.MIERCOLES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.JUEVES),
                                new CreateScheduleRequest(LocalTime.of(7, 0, 0), LocalTime.of(12, 0, 0), Workday.VIERNES)
                        )
                ),
                null,
                List.of(Role.DOCTOR)
        ));

        System.out.println("✔ Médicos de prueba insertados");
    }
}
 */