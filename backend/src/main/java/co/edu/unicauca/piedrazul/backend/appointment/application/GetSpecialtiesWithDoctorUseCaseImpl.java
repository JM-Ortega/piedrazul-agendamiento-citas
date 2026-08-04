package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetSpecialtiesWithDoctorUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.appointment.exception.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GetSpecialtiesWithDoctorUseCaseImpl implements GetSpecialtiesWithDoctorUseCase {

    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final SlotTimeService slotTimeService;
    private final IsNewPatientUseCase isNewPatientUseCase;

    public GetSpecialtiesWithDoctorUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            SlotTimeService slotTimeService,
            IsNewPatientUseCase isNewPatientUseCase) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.slotTimeService = slotTimeService;
        this.isNewPatientUseCase = isNewPatientUseCase;
    }

    @Override
    public List<DoctorResponse> getSpecialtiesWithDoctor(UUID patientId) {
        LocalDate from = LocalDate.now();

        List<UUID> activeDoctorIds = isNewPatientUseCase.isNewPatient(patientId)
                ? getActiveGeneralDoctorsOrThrow()
                : getActiveDoctorsOrThrow();

        Map<UUID, Integer> availability = calculateAvailability(activeDoctorIds, from);

        List<UUID> orderedDoctors = sortDoctorsByAvailability(availability);

        Map<UUID, DoctorResponse> doctorMap = loadAndNormalizeDoctors(orderedDoctors);

        return selectUniqueSpecialties(orderedDoctors, doctorMap);
    }

    private List<UUID> getActiveDoctorsOrThrow() {
        List<UUID> doctors = doctorConfigConsultPort.getActiveDoctorIds();

        if (doctors.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay medicos activos con disponibilidad.");
        }

        return doctors;
    }

    private List<UUID> getActiveGeneralDoctorsOrThrow() {
        List<UUID> doctors = doctorConfigConsultPort.getActiveGeneralDoctorIds();

        if (doctors.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay medicos activos con disponibilidad.");
        }

        return doctors;
    }

    private Map<UUID, Integer> calculateAvailability(List<UUID> doctorIds, LocalDate from) {

        Map<UUID, Integer> bookingWindowWeeks =
                doctorConfigConsultPort.getBookingWindowWeeksByDoctorIds(doctorIds);
        Map<UUID, Integer> intervalMinutes =
                doctorConfigConsultPort.getIntervalMinutesByDoctorIds(doctorIds);

        for (UUID doctorId : doctorIds) {
            if (!bookingWindowWeeks.containsKey(doctorId)) {
                throw new DoctorConfigInconsistentException(
                        "No se encontró bookingWindowWeeks para el doctor: " + doctorId);
            }
            if (!intervalMinutes.containsKey(doctorId)) {
                throw new DoctorConfigInconsistentException(
                        "No se encontró appointmentInterval para el doctor: " + doctorId);
            }
        }

        Map<UUID, Integer> result = doctorIds.stream()
                .map(id -> {
                    LocalDate to = from.plusWeeks(bookingWindowWeeks.get(id));
                    int interval = intervalMinutes.get(id);
                    return Map.entry(id, countAvailableSlotsForPeriod(id, from, to, interval));
                })
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (result.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay medicos con espacios disponibles en el rango solicitado.");
        }

        return result;
    }

    private List<UUID> sortDoctorsByAvailability(Map<UUID, Integer> availability) {
        return availability.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<UUID, DoctorResponse> loadAndNormalizeDoctors(List<UUID> doctorIds) {
        return doctorConfigConsultPort.getDoctorInfoByIds(doctorIds).stream()
                .map(this::normalizeDoctorResponse)
                .collect(Collectors.toMap(
                        DoctorResponse::id,
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private List<DoctorResponse> selectUniqueSpecialties(
            List<UUID> orderedDoctorIds,
            Map<UUID, DoctorResponse> doctorMap) {

        Set<String> usedSpecialties = new HashSet<>();

        return orderedDoctorIds.stream()
                .map(doctorMap::get)
                .filter(Objects::nonNull)
                .map(doctor -> buildDoctorWithUniqueSpecialty(doctor, usedSpecialties))
                .filter(Objects::nonNull)
                .toList();
    }

    private DoctorResponse buildDoctorWithUniqueSpecialty(
            DoctorResponse doctor,
            Set<String> usedSpecialties) {

        return doctor.specialty().stream()
                .filter(s -> !usedSpecialties.contains(s))
                .findFirst()
                .map(specialty -> {
                    usedSpecialties.add(specialty);
                    return new DoctorResponse(
                            List.of(specialty),
                            doctor.id(),
                            doctor.name(),
                            doctor.laborEnd(),
                            doctor.laborStart(),
                            doctor.workdays()
                    );
                })
                .orElse(null);
    }

    private int countAvailableSlotsForPeriod(UUID doctorId, LocalDate from, LocalDate to, int interval) {
        return from.datesUntil(to.plusDays(1))
                .filter(this::isWeekday)
                .mapToInt(date -> safeCountSlotsForDay(doctorId, date, interval))
                .sum();
    }

    private int safeCountSlotsForDay(UUID doctorId, LocalDate date, int interval) {
        try {
            return countAvailableSlotsForDay(doctorId, date, interval);
        } catch (Exception e) {
            return 0;
        }
    }

    private int countAvailableSlotsForDay(UUID doctorId, LocalDate date, int interval) {
        List<AppointmentTime> slots = doctorConfigConsultPort.getSlotsByDoctor(doctorId, date);
        List<Appointment> appointments = appointmentRepository.findByDoctorIdAndDate(doctorId, date);

        return slotTimeService.calculateAvailable(slots, appointments, interval).size();
    }

    private boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek().getValue() <= 5;
    }

    private DoctorResponse normalizeDoctorResponse(DoctorResponse doctor) {
        List<Integer> workdays = Optional.ofNullable(doctor.workdays())
                .orElse(List.of())
                .stream()
                .filter(day -> day >= 1 && day <= 5)
                .distinct()
                .sorted()
                .toList();

        List<String> specialties = Optional.ofNullable(doctor.specialty()).orElse(List.of());
        List<String> normalizedSpecialties = specialties.isEmpty()
                ? List.of("SIN_ESPECIALIDAD")
                : specialties; // se conservan TODAS, no solo la primera

        return new DoctorResponse(
                normalizedSpecialties,
                doctor.id(),
                doctor.name(),
                doctor.laborEnd(),
                doctor.laborStart(),
                workdays
        );
    }
}
