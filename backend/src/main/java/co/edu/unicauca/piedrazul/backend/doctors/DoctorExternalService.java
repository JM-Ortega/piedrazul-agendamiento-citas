package co.edu.unicauca.piedrazul.backend.doctors;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorAdminUserData;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

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

    List<DoctorResponse> getActiveDoctors ();

    List<UUID> getActiveDoctorIds();

    List<UUID> getActiveGeneralDoctorIds();

    List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds);

    List<DoctorAdminUserData> getAdminUserData();
}
