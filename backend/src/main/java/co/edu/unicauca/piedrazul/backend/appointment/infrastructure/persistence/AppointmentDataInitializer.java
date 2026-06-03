package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;
/*
import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;


@Component
@Order(3)
 */
public class AppointmentDataInitializer /*implements ApplicationRunner*/ {
    /*
    private final AppointmentSchedulingService appointmentSchedulingService;
    private final AppointmentJpaRepository appointmentJpaRepository;
    private final DoctorExternalService doctorExternalService;

    private final AppointmentRepositoryImpl appointmentRepositoryImpl;
    private final AppointmentService appointmentService;

    public AppointmentDataInitializer(AppointmentJpaRepository appointmentJpaRepository,
                                      AppointmentSchedulingService appointmentSchedulingService,
                                      DoctorExternalService doctorExternalService,
                                      AppointmentRepositoryImpl appointmentRepositoryImpl,
                                      AppointmentService appointmentService){
        this.appointmentJpaRepository = appointmentJpaRepository;
        this.appointmentSchedulingService =appointmentSchedulingService;
        this.doctorExternalService = doctorExternalService;
        this.appointmentRepositoryImpl = appointmentRepositoryImpl
        this.appointmentService = appointmentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (appointmentJpaRepository.count() > 0) {
            return;
        }

        UUID idDoc2 = doctorExternalService.findIdByIdentification("11000001");
        UUId idPac1 =

        Appointment appointment = appointmentService.scheduleManual(
                new AppointmentSchedulingRequest(
                        idClara,
                        "Clara Inés Cordoba"

                )
        )

        appointmentRepositoryImpl.save(new Appointment()
        )

        appointmentSchedulingService.schedule(
                PatientSchedulingContext.manual(
                        DocumentType.CEDULA,
                        "33000005",
                        "María",
                        "López",
                        "3001234567",
                        Gender.FEMENINO,
                        LocalDate.of(1990, 5, 15),
                        "maria.lopez@email.com",
                        null
                ),
                idClara,
                doctorExternalService.findSpecialtiesByIdentification("11000001"),
                request.getDate(),
                new AppointmentTime(request.getStartTime()),
                performedBy,
                true

        appointmentJpaRepository.save(new AppointmentEntity(
                null,

                )
        )

    }
     */
}
