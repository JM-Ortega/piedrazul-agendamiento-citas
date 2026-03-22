package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
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
    public void save(Appointment appointment) {
        jpaRepository.save(mapper.toEntity(appointment));
    }

    @Override
    public void deleteById(UUID idCita) {
        jpaRepository.deleteById(idCita);
    }

    @Override
    public List<Appointment> findByDoctorId(UUID idDoctor) {
        return jpaRepository.findByIdDoctor(idDoctor).stream().map(mapper::toDomain).toList();
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
    public boolean existsByDoctorDateAndTime(UUID idDoctor, LocalDate date, AppointmentTime startTime) {
        return jpaRepository.existsByIdDoctorAndDateAndStartTime(idDoctor, date, startTime.getTime());
    }

}
