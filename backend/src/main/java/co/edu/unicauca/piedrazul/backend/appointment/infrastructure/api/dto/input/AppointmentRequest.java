package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
public class AppointmentRequest {
    @NotNull(message = "El médico es obligatorio")
    private UUID doctorId;

    @NotNull(message = "La especialidad es obligatoria")
    private Specialty specialty;

    @NotNull(message = "La fecha es obligatoria")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @NotNull(message = "La hora es obligatoria")
    private LocalTime startTime;

    @NotNull(message = "El origen es obligatorio")
    private SchedulingOrigin schedulingOrigin;

    // Solo obligatorio en agendamiento autónomo
    private UUID patientId;

    // Solo obligatorios en agendamiento manual
    private DocumentType documentType;
    private String documentNumber;
    private String firstName;
    private String lastName;
    private String phone;
    private Gender gender;
    private LocalDate birthDate; // opcional
    private String email;        // opcional
    private String guardianPhone; // opcional, para pacientes menores de edad

    // Se valida dependiendo del tipo de agendamiento
    public void validate() {
        if (schedulingOrigin == SchedulingOrigin.AUTONOMO
                && patientId == null) {
            throw new IllegalArgumentException(
                    "El id del paciente es obligatorio para agendamiento autónomo"
            );
        }
        if (schedulingOrigin == SchedulingOrigin.MANUAL
                && (documentNumber == null || firstName == null || phone == null)) {
            throw new IllegalArgumentException(
                    "El numero de documento, nombre completo y teléfono son obligatorios para agendamiento manual"
            );
        }
    }
}
