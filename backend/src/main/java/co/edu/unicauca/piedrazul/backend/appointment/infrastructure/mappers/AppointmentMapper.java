package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import org.springframework.stereotype.Component;

@Component
public class AppointmentMapper {

    //De dominio a JPA
    public AppointmentEntity toEntity(Appointment appointment) {
        if (appointment == null) {
            return null;
        }

        //Creo la entidad JPA
        AppointmentEntity appointmentEntity = new AppointmentEntity();

        //Mapeo los valores de la entidad de dominio a la entidad JPA
        appointmentEntity.setIdCita(appointment.getIdAppointment());
        appointmentEntity.setIdDoctor(appointment.getIdDoctor());
        appointmentEntity.setIdPatient(appointment.getIdPatient());
        appointmentEntity.setPatientInfo(appointment.getPatientInfo());
        appointmentEntity.setSpecialty(appointment.getSpecialty());
        appointmentEntity.setAppointmentState(appointment.getAppointmentState());
        appointmentEntity.setDate(appointment.getDate());
        appointmentEntity.setStartTime(appointment.getStartTime());
        appointmentEntity.setSchedulingOrigin(appointment.getSchedulingOrigin());

        return appointmentEntity;

    }

    //de JPA  dominio
    public AppointmentEntity toDomain(AppointmentEntity appointmentEntity) {

        return new Appointment(
                appointmentEntity.getIdCita(),
                appointmentEntity.getIdDoctor(),
                appointmentEntity.getIdPatient(),
                appointmentEntity.getPatientInfo(),
                appointmentEntity.getSpecialty(),
                appointmentEntity.getAppointmentState(),
                appointmentEntity.getDate(),
                appointmentEntity.getStartTime(),
                appointmentEntity.getSchedulingOrigin()
        );

    }
}
