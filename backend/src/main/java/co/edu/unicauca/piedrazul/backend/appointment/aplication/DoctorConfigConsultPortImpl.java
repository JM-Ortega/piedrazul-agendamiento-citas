package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public class DoctorConfigConsultPortImpl implements DoctorConfigConsultPort {
    private final DoctorExternalService doctorExternalService;

    public DoctorConfigConsultPortImpl(DoctorExternalService doctorExternalService) {
        this.doctorExternalService = doctorExternalService;
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
}
