package co.edu.unicauca.piedrazul.backend.doctors;

import co.edu.unicauca.piedrazul.backend.doctors.domain.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DoctorExternalService {
    List<LocalTime> getSlotsByDoctor(UUID idDoctor, LocalDate date);

    int getIntervalMinutesByDoctor(UUID idDoctor);

    List<DoctorResponse> getActiveDoctors ();

    List<UUID> getActiveDoctorIds();

    List<UUID> getActiveGeneralDoctorIds();

    List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds);

    List<SpecialtyCode> findSpecialtiesByIdentification(String identification);

    Map<UUID, Integer> bookingWindowWeeksByDoctorIds(List<UUID> doctorIds);

    Map<UUID, Integer> intervalMinutesByDoctorIds(List<UUID> doctorIds);
}
