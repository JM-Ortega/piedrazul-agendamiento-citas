package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
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
        appointmentEntity.setIdAppointment(appointment.getIdAppointment());
        appointmentEntity.setIdDoctor(appointment.getIdDoctor());
        appointmentEntity.setIdPatient(appointment.getIdPatient());
        appointmentEntity.setSpecialty(appointment.getSpecialty());
        appointmentEntity.setAppointmentState(appointment.getAppointmentState());
        appointmentEntity.setDate(appointment.getDate());
        appointmentEntity.setStartTime(appointment.getStartTime().getTime());
        appointmentEntity.setSchedulingOrigin(appointment.getSchedulingOrigin());

        return appointmentEntity;
    }

    //de JPA a dominio
    public Appointment toDomain(AppointmentEntity entity) {

        if(entity == null) {
            return null;
        }

        AppointmentTime appointmentTime = new AppointmentTime(entity.getStartTime());

        return Appointment.reconstruct(
                entity.getIdAppointment(),
                entity.getIdDoctor(),
                entity.getIdPatient(),
                entity.getSpecialty(),
                entity.getAppointmentState(),
                entity.getDate(),
                appointmentTime,
                entity.getSchedulingOrigin()
        );
    }
}
