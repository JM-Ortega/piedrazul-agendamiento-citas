package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
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
    public List<LocalTime> getSlotsByDoctor(UUID idDoctor, LocalDate date) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        return scheduleService.getAvailableIntervalsByWorkday(
                doctor,
                toWorkday(date.getDayOfWeek())
        );
    }

    @Override
    public int getIntervalMinutesByDoctor(UUID idDoctor) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        return doctor.getAppointmentInterval();
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
                .map(Doctor::getPersonId)
                .toList();
    }


    @Override
    public List<UUID> getActiveGeneralDoctorIds() {
        return doctorRepository.findByStatusTrue()
                .stream()
                .filter(doctor -> doctor.tieneEspecialidad(SpecialtyCode.MEDICINA_GENERAL))
                .map(Doctor::getPersonId)
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
    public List<SpecialtyCode> findSpecialtiesByIdentification(String identification){
        return doctorRepository.findSpecialtiesByIdentification(identification);
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
