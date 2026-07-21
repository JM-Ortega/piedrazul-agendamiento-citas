package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.proyections.DoctorSpecialtyProjection;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.DayOfWeek;
import java.util.stream.Collectors;

// Servicio para peticiones externas
public class DoctorExternalServiceImpl implements DoctorExternalService {
    private final DoctorRepository doctorRepository;
    private final ScheduleService scheduleService;
    private final PersonExternalService personExternalService;

    public DoctorExternalServiceImpl(DoctorRepository doctorRepository, ScheduleService scheduleService,
                                     PersonExternalService personExternalService) {
        this.doctorRepository = doctorRepository;
        this.scheduleService = scheduleService;
        this.personExternalService = personExternalService;
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

    // ELIMINAR parece que no se usa
    @Override
    public List<DoctorResponse> getActiveDoctors (){
        List<Doctor> doctors = doctorRepository.findByStatusTrue();

        List<UUID> ids = doctors.stream()
                .map(Doctor::getPersonId)
                .toList();

        Map<UUID, String> names = personExternalService.getPersonNames(ids);

        return doctors.stream()
                .map(d -> DoctorResponse.fromEntity(d, names.get(d.getPersonId())))
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
                .filter(doctor -> doctor.hasSpecialtie(SpecialtyCode.MEDICINA_GENERAL))
                .map(Doctor::getPersonId)
                .toList();
    }

    @Override
    @Transactional
    public List<DoctorResponse> getDoctorInfoByIds(List<UUID> doctorIds) {
        if (doctorIds == null || doctorIds.isEmpty()) {
            return List.of();
        }

        List<Doctor> doctors = doctorRepository.findByPersonIdIn(doctorIds);

        Map<UUID, String> names = personExternalService.getPersonNames(doctorIds);

        return doctors.stream()
                .map(d -> DoctorResponse.fromEntity(d, names.get(d.getPersonId())))
                .toList();
    }

    @Override
    public Map<UUID, List<SpecialtyCode>> findSpecialtiesByPersonIds(Collection<UUID> personIds){

        List<DoctorSpecialtyProjection> projections = doctorRepository.findSpecialtiesByPersonIds(personIds);

        return projections.stream()
                .collect(Collectors.groupingBy(
                        DoctorSpecialtyProjection::personId,
                        Collectors.mapping(
                                DoctorSpecialtyProjection::specialty,
                                Collectors.toList()
                        )
                ));
    }

    @Override
    public Map<UUID, Integer> bookingWindowWeeksByDoctorIds(List<UUID> doctorIds){
        return doctorRepository.findAllById(doctorIds).stream()
                .collect(Collectors.toMap(Doctor::getPersonId, Doctor::getBookingWindowWeeks));
    }

    @Override
    public Map<UUID, Integer> intervalMinutesByDoctorIds(List<UUID> doctorIds) {
        return doctorRepository.findAllById(doctorIds).stream()
                .collect(Collectors.toMap(Doctor::getPersonId, Doctor::getAppointmentInterval));
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
