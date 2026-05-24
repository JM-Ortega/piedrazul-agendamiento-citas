package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppointmentJpaRepository extends JpaRepository<AppointmentEntity, UUID> {

    List<AppointmentEntity> findByIdDoctor(UUID idDoctor);

    List<AppointmentEntity> findByIdPatient(UUID idPatient);

    List<AppointmentEntity> findByDate(LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDate(UUID idDoctor, LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDateAndAppointmentState(UUID idDoctor, LocalDate date, AppointmentState appointmentState);

    List<AppointmentEntity> findByIdPatientAndDate(UUID idPatient, LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndIdPatient(UUID idDoctor, UUID idPatient);

    List<AppointmentEntity> findByIdDoctorAndIdPatientAndDate(UUID idDoctor, UUID idPatient, LocalDate date);

    boolean existsByIdPatientAndAppointmentStateIn(UUID idPatient, Collection<AppointmentState> appointmentStates);


}
