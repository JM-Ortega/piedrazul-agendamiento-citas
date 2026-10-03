package co.edu.unicauca.piedrazul.backend.doctors;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingSchedule;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.time.LocalDate;
import java.util.*;

public interface DoctorExternalService {

    Optional<UUID> findByUserId(UUID userId);

    int getIntervalMinutesByDoctor(UUID idDoctor);

    List<UUID> getActiveDoctorIds();

    List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds);

    Map<UUID, List<SpecialtyCode>> findSpecialtiesByPersonIds(Collection<UUID> personIds);

    Map<UUID, Integer> bookingWindowWeeksByDoctorIds(List<UUID> doctorIds);

    Map<UUID, Integer> intervalMinutesByDoctorIds(List<UUID> doctorIds);

    // Retorna las fechas y slots y el intervalo en las que el doctor trabaja
    WorkingSchedule workingSchedule(UUID idDoctor);

    // Indica si la fecha cae dentro de un periodo de descanso del doctor
    boolean isOnTimeOff(UUID idDoctor, LocalDate date);
}
