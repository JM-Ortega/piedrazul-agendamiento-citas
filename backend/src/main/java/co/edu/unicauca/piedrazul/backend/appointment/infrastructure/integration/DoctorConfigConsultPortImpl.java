package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class DoctorConfigConsultPortImpl implements DoctorConfigConsultPort {
    private final DoctorExternalService doctorExternalService;

    public DoctorConfigConsultPortImpl(DoctorExternalService doctorExternalService) {
        this.doctorExternalService = doctorExternalService;
    }

    @Override
    public Optional<UUID> findByUserId(UUID userId) {
        return doctorExternalService.findByUserId(userId);
    }

    @Override
    public List<AppointmentTime> getSlotsByDoctor(UUID idDoctor, LocalDate date) {
        List<LocalTime> slots = doctorExternalService.getSlotsByDoctor(idDoctor, date);

        return slots.stream()
                .map(AppointmentTime::new)
                .toList();
    }

    @Override
    public int getIntervalMinutesByDoctor(UUID idDoctor) {
        return doctorExternalService.getIntervalMinutesByDoctor(idDoctor);
    }

    @Override
    public List<UUID> getActiveDoctorIds() {
        return doctorExternalService.getActiveDoctorIds();
    }

    @Override
    public List<UUID> getActiveGeneralDoctorIds(){
        return doctorExternalService.getActiveGeneralDoctorIds();
    }


    @Override
    public String getDoctorName(UUID idDoctor){
        List<DoctorResponse> doctors = doctorExternalService.getDoctorInfoByIds(List.of(idDoctor));
        return doctors.isEmpty() ? null : doctors.get(0).name();
    }

    @Override
    public List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds) {
        return doctorExternalService.getDoctorInfoByIds(doctorIds);
    }

    @Override
    public Map<UUID, Integer> getBookingWindowWeeksByDoctorIds(List<UUID> doctorIds) {
        return doctorExternalService.bookingWindowWeeksByDoctorIds(doctorIds);
    }

    @Override
    public Map<UUID, Integer> getIntervalMinutesByDoctorIds(List<UUID> doctorIds) {
        return doctorExternalService.intervalMinutesByDoctorIds(doctorIds);
    }

    @Override
    public List<SpecialtyCode> getSpecialtiesByDoctor(UUID idDoctor) {
        return doctorExternalService.findSpecialtiesByPersonIds(List.of(idDoctor)).getOrDefault(idDoctor, List.of());
    }
}
