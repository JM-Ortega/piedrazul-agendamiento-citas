package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.time.DayOfWeek;

// Lombok
@RequiredArgsConstructor

//Servicio para peticiones externas
@Service
public class DoctorExternalServiceImpl implements DoctorExternalService {
    private final DoctorRepository doctorRepository;
    private final ScheduleService scheduleService;

    @Override
    public boolean existDoctor(UUID idDoctor) {
        return doctorRepository.existsById(idDoctor);
    }

    @Override
    public String doctorsName(UUID idDoctor) {
        return doctorRepository.findByIdDoctor(idDoctor).getFirstName() + " " + doctorRepository.findByIdDoctor(idDoctor).getLastName();
    }

    @Override
    public List<LocalTime> getSlotsByDoctor(UUID idDoctor, LocalDate date) {
        return scheduleService.getAvailableIntervalsByWorkday(doctorRepository.findByIdDoctor(idDoctor), toWorkday(date.getDayOfWeek()));
    }

    @Override
    public int getIntervalMinutesByDoctor(UUID idDoctor) {
        return doctorRepository.findByIdDoctor(idDoctor).getAppointmentInterval();
    }

    @Override
    public String getDoctorName(UUID idDoctor) {
        return doctorRepository.findByIdDoctor(idDoctor).getFirstName() + " " + doctorRepository.findByIdDoctor(idDoctor).getLastName();
    }

    private static Workday toWorkday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY    -> Workday.LUNES;
            case TUESDAY   -> Workday.MARTES;
            case WEDNESDAY -> Workday.MIERCOLES;
            case THURSDAY  -> Workday.JUEVES;
            case FRIDAY    -> Workday.VIERNES;
            // Si tu enum Workday no tiene fines de semana, puedes lanzar la excepción aquí
            default -> throw new IllegalArgumentException("Día no laboral o inesperado: " + dayOfWeek);
        };
    }

}
