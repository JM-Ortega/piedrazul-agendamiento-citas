package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.doctors.model.services.DoctorExternalService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class AppointmentRepositoryImpl implements AppointmentRepository {

    private final AppointmentJpaRepository jpaRepository;
    private final AppointmentMapper mapper;
    //hace falta importar las interfaces de los modulos de doctor y paciente
    //para poder usar los metodos findById y obtener los nombres
    private final DoctorExternalService doctorPort;
    //falta el de Juan



    public AppointmentRepositoryImpl(AppointmentJpaRepository jpaRepository, AppointmentMapper mapper, DoctorExternalService doctorPort) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.doctorPort = doctorPort;
    }


    @Override
    public Appointment save(Appointment appointment) {

        // Doctor siempre tiene cuenta, siempre consulto el modulo
        String doctorName = doctorPort.doctorsName(appointment.getIdDoctor()).getFullName();

        String patientName;

        //Esto cambia segun la interfaz y el metodo que Juan exponga
        if (appointment.getIdPatient() == null) {
            // MANUAL — el paciente no tiene cuenta
            // el nombre se contruye desde el VO PatientInfo
            patientName = appointment.getPatientInfo().getFirstName()
                    + " " + appointment.getPatientInfo().getLastName();
        } else {
            // AUTÓNOMA — el paciente sí tiene cuenta
            // consultas el nombre al módulo de patients
            patientName = patientPort.patientName(
                    appointment.getIdPatient()
            ).getFullName();
        }

        AppointmentEntity entity = mapper.toEntity(
                appointment, doctorName, patientName
        );


        jpaRepository.save(mapper.toEntity(appointment, doctorName, patientName, patientName));

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
        return jpaRepository.existsByIdDoctorDateAndTime(idDoctor, date, startTime.getTime());
    }

}
