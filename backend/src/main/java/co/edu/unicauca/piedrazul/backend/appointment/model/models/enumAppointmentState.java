package co.edu.unicauca.piedrazul.backend.appointment.model.models;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

//Appot is an abbreviation for Appointment
public enum enumAppointmentState {
    AGENDADA,
    ATENDIDA,
    CANCELADA,
    NO_ASISTIO,
    REPROGRAMADA
}
