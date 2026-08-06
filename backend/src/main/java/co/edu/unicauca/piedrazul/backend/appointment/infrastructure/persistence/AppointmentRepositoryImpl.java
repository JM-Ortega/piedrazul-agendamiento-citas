package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class AppointmentRepositoryImpl implements AppointmentRepository {

    private final AppointmentJpaRepository jpaRepository;
    private final AppointmentMapper mapper;

    public AppointmentRepositoryImpl(AppointmentJpaRepository jpaRepository, AppointmentMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Transactional
    @Override
    public Appointment save(Appointment appointment) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(appointment)));
    }

    @Override
    public List<Appointment> findByPatientId(UUID idPatient) {
        return jpaRepository.findByIdPatient(idPatient).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Appointment> findByDoctorIdAndDate(UUID idDoctor, LocalDate date) {
        return jpaRepository.findByIdDoctorAndDate(idDoctor, date).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Appointment> findByDoctorIdAndDateAndState(UUID idDoctor, LocalDate date, String state) {
        return jpaRepository
                .findByIdDoctorAndDateAndAppointmentState(idDoctor, date, AppointmentState.valueOf(state))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Appointment> findByPatientIdAndDate(UUID idPatient, LocalDate date) {
        return jpaRepository.findByIdPatientAndDate(idPatient, date).stream().map(mapper::toDomain).toList();
    }

    @Override
    public boolean existsByPatientIdAndStates(UUID idPatient, Collection<AppointmentState> states) {
        return jpaRepository.existsByIdPatientAndAppointmentStateIn(idPatient, states);
    }

    @Override
    public Appointment findById(UUID appointmentId) {
        return jpaRepository.findById(appointmentId)
                .map(mapper::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("Cita con ID: " + appointmentId + "no encontrada"));
    }

    @Override
    public UUID getPattientIdByAppointmentId(UUID appointmentId){
        return jpaRepository.findById(appointmentId).get().getIdPatient();
    }
  
    @Override
    public List<Appointment> findAllByDate(LocalDate date) { return jpaRepository.findByDate(date)
            .stream()
            .map(mapper::toDomain)
            .toList();
    }

    @Override
    public List<Appointment> findScheduledAppointmentsBefore(LocalDate date) {
        return jpaRepository.findByAppointmentStateAndDateBefore(AppointmentState.AGENDADA, date)
                .stream().map(mapper::toDomain)
                .toList();
    }


    @Override
    public List<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state) {
        List<Specification<AppointmentEntity>> specs = new ArrayList<>();

        if (idDoctor != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("idDoctor"), idDoctor));
        }
        if (idPatient != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("idPatient"), idPatient));
        }
        if (date != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("date"), date));
        }
        if (state != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("appointmentState"), state));
        }

        Specification<AppointmentEntity> spec = Specification.allOf(specs);

        return jpaRepository.findAll(spec).stream()
                .map(mapper::toDomain)
                .toList();
    }

}
