package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
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
    public void save(Appointment appointment) {
        jpaRepository.save(mapper.toEntity(appointment));
    }

    //metodo para el modulo de historia clinica
    @Override
    public Optional<Appointment> findById(UUID idAppointment) {
        return jpaRepository.findById(idAppointment).map(mapper::toDomain);
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
    public List<Appointment> findAll(){
        return jpaRepository.findAll().stream().map(mapper::toDomain).toList();
    }
}
