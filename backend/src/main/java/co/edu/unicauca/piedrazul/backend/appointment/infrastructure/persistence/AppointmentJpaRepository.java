package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AppointmentJpaRepository extends JpaRepository<AppointmentEntity, UUID>, JpaSpecificationExecutor<AppointmentEntity> {

    List<AppointmentEntity> findByIdPatient(UUID idPatient);

    List<AppointmentEntity> findByDate(LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDate(UUID idDoctor, LocalDate date);

    List<AppointmentEntity> findByIdDoctorAndDateAndAppointmentState(UUID idDoctor, LocalDate date, AppointmentState appointmentState);

    List<AppointmentEntity> findByIdPatientAndDate(UUID idPatient, LocalDate date);

    List<AppointmentEntity> findByAppointmentStateAndDateBefore(AppointmentState state, LocalDate date);

    boolean existsByIdPatientAndAppointmentStateIn(UUID idPatient, Collection<AppointmentState> appointmentStates);


}
