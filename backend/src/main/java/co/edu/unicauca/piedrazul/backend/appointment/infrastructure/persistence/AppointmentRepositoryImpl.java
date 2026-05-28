package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
    public List<Appointment> findByDoctorId(UUID idDoctor) {
        return jpaRepository.findByIdDoctor(idDoctor).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Appointment> findByPatientId(UUID idPatient) {
        return jpaRepository.findByIdPatient(idPatient).stream().map(mapper::toDomain).toList();
    }


    @Override
    public List<Appointment> findByDate(LocalDate date) {
        return jpaRepository.findByDate(date).stream().map(mapper::toDomain).toList();
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
    public List<Appointment> findByDoctorIdAndPatientId(UUID idDoctor, UUID idPatient) {
        return jpaRepository.findByIdDoctorAndIdPatient(idDoctor, idPatient).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Appointment> findByDoctorIdAndPatientIdAndDate(UUID idDoctor, UUID idPatient, LocalDate date) {
        return jpaRepository.findByIdDoctorAndIdPatientAndDate(idDoctor, idPatient, date).stream().map(mapper::toDomain).toList();
    }

    @Override
    public boolean existsByPatientIdAndStates(UUID idPatient, Collection<AppointmentState> states) {
        return jpaRepository.existsByIdPatientAndAppointmentStateIn(idPatient, states);
    }

    @Override
    public List<Appointment> findAll(){
        return jpaRepository.findAll().stream().map(mapper::toDomain).toList();
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
}
