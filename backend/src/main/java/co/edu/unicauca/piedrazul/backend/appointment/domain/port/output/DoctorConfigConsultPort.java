package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorPublicInfo;

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

    // Obtener informacion de medicos en una sola operacion por sus IDs
    List<DoctorPublicInfo> getDoctorInfoByIds(List<UUID> doctorIds);
}
