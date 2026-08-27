package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

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

    // Obtener IDs de medicos activos
    List<UUID> getActiveDoctorIds();

    // Obtener IDs de medicos generales activos
    List<UUID> getActiveGeneralDoctorIds();

    // Obtener información de medicos en una sola operación por sus IDs
    List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds);
}
