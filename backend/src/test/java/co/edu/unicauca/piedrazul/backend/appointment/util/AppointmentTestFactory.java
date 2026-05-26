package co.edu.unicauca.piedrazul.backend.appointment.util;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public class AppointmentTestFactory {

    public static final UUID DOCTOR_ID = UUID.randomUUID();
    public static final UUID PATIENT_ID = UUID.randomUUID();
    public static final LocalDate FUTURE_DATE = LocalDate.now().plusDays(5);
    public static final LocalTime START_TIME = LocalTime.of(9, 0);

    // Crea una AppointmentEntity lista para persistir directamente
    public static AppointmentEntity buildEntity() {
        AppointmentEntity entity = new AppointmentEntity();
        entity.setIdDoctor(DOCTOR_ID);
        entity.setDoctorName("Dr. Juan Pérez");
        entity.setIdPatient(PATIENT_ID);
        entity.setPatientName("Carlos López");
        entity.setSpecialty(Specialty.FISIOTERAPIA);
        entity.setAppointmentState(AppointmentState.AGENDADA);
        entity.setDate(FUTURE_DATE);
        entity.setStartTime(START_TIME);
        entity.setSchedulingOrigin(SchedulingOrigin.MANUAL);
        return entity;
    }

    // Crea un PatientInfo válido para pruebas
    public static PatientInfo buildPatientInfo() {
        return PatientInfo.of(
                DocumentType.CEDULA,
                "12345678",
                "Carlos",
                "López",
                "3001234567",
                Gender.MASCULINO,
                LocalDate.of(1990, 5, 15),
                "carlos@email.com",
                null
        );
    }
}