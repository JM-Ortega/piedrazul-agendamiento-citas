package co.edu.unicauca.piedrazul.backend.doctors;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface DoctorExternalService {
    boolean existDoctor(UUID idDoctor);

    String doctorsName(UUID idDoctor);

    List<LocalTime> getSlotsByDoctor(UUID idDoctor, LocalDate date);

    int getIntervalMinutesByDoctor(UUID idDoctor);

    String getDoctorName(UUID idDoctor);

    List<DoctorPublicInfo> getActiveDoctors ();

    List<UUID> getActiveDoctorIds();

    List<DoctorPublicInfo> getDoctorInfoByIds(List<UUID> doctorIds);
}
