package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorAdminUserData;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.time.DayOfWeek;

// Servicio para peticiones externas
public class DoctorExternalServiceImpl implements DoctorExternalService {
    private final DoctorRepository doctorRepository;
    private final ScheduleService scheduleService;

    public DoctorExternalServiceImpl(DoctorRepository doctorRepository, ScheduleService scheduleService) {
        this.doctorRepository = doctorRepository;
        this.scheduleService = scheduleService;
    }

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

    @Override
    public List<DoctorResponse> getActiveDoctors (){
        return doctorRepository.findByStatusTrue()
                .stream()
                .map(DoctorResponse::fromEntity)
                .toList();
    }

    @Override
    public List<UUID> getActiveDoctorIds() {
        return doctorRepository.findByStatusTrue()
                .stream()
                .map(Doctor::getIdDoctor)
                .toList();
    }

    @Override
    @Transactional
    public List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds) {
        if (doctorIds == null || doctorIds.isEmpty()) {
            return List.of();
        }

        return doctorRepository.findByIdDoctorIn(doctorIds).stream()
                .map(DoctorResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional
    public List<DoctorAdminUserData> getAdminUserData() {
        return doctorRepository.findAll()
                .stream()
                .map(DoctorAdminUserData::fromEntity)
                .toList();
    }

    private static Workday toWorkday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY    -> Workday.LUNES;
            case TUESDAY   -> Workday.MARTES;
            case WEDNESDAY -> Workday.MIERCOLES;
            case THURSDAY  -> Workday.JUEVES;
            case FRIDAY    -> Workday.VIERNES;

            default -> throw new IllegalArgumentException("Día no laboral o inesperado: " + dayOfWeek);
        };
    }
}
