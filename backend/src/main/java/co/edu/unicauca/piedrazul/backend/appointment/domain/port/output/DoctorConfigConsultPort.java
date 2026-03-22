package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DoctorConfigConsultPort {
    // Devuelve las franjas horarias que maneja el médico ese día
    List<AppointmentTime> getSlotsByDoctor(UUID idDoctor, LocalDate date);

    // Devuelve el intervalo entre citas configurado para el médico
    int getIntervalMinutesByDoctor(UUID idDoctor);

    // Obtener el nombre del doctor
    String getDoctorName(UUID idDoctor);
}
