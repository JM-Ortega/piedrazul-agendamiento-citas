package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingSchedule;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DoctorConfigConsultPort {

    //Obtiene el id del doctor a través de el id de usuario
    Optional<UUID> findByUserId(UUID userId);

    // Retorna las fechas y slots y el intervalo en las que el doctor trabaja
    WorkingSchedule workingSchedule(UUID idDoctor);

    // Devuelve el intervalo entre citas configurado para el médico
    int getIntervalMinutesByDoctor(UUID idDoctor);

    // Obtener IDs de medicos activos
    List<UUID> getActiveDoctorIds();

    // Obtener IDs de medicos generales activos
    List<UUID> getActiveGeneralDoctorIds();

    // Obtener información de medicos en una sola operación por sus IDs
    List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds);

    // Obtiene las semanas agendables asociadas al doctor
    Map<UUID, Integer> getBookingWindowWeeksByDoctorIds(List<UUID> doctorIds);

    // Obtiene el intervalo de semanas asociadas al doctor
    Map<UUID, Integer> getIntervalMinutesByDoctorIds(List<UUID> doctorIds);

    //obtiene el nombre de un doctor
    String getDoctorName(UUID idDoctor);

    List<SpecialtyCode> getSpecialtiesByDoctor(UUID idDoctor);
}
