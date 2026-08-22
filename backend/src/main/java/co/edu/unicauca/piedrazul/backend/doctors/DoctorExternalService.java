package co.edu.unicauca.piedrazul.backend.doctors;

import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

public interface DoctorExternalService {

    Optional<UUID> findByUserId(UUID userId);

    List<LocalTime> getSlotsByDoctor(UUID idDoctor, LocalDate date);

    int getIntervalMinutesByDoctor(UUID idDoctor);

    List<DoctorResponse> getActiveDoctors ();

    List<UUID> getActiveDoctorIds();

    List<UUID> getActiveGeneralDoctorIds();

    List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds);

    Map<UUID, List<SpecialtyCode>> findSpecialtiesByPersonIds(Collection<UUID> personIds);

    Map<UUID, Integer> bookingWindowWeeksByDoctorIds(List<UUID> doctorIds);

    Map<UUID, Integer> intervalMinutesByDoctorIds(List<UUID> doctorIds);
}
