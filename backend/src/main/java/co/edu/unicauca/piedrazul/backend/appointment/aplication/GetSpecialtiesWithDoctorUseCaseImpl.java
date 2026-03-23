package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.NoAvailableDoctorsException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetSpecialtiesWithDoctorUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GetSpecialtiesWithDoctorUseCaseImpl implements GetSpecialtiesWithDoctorUseCase {
    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;

    public GetSpecialtiesWithDoctorUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
    }

    @Override
    public List<DoctorResponse> getSpecialtiesWithDoctor() {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusMonths(1);

        List<UUID> activeDoctorIds = doctorConfigConsultPort.getActiveDoctorIds();
        if (activeDoctorIds.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay medicos activos con disponibilidad.");
        }

        Map<UUID, Integer> availableSlotsByDoctor = new HashMap<>();

        for (UUID doctorId : activeDoctorIds) {
            int availableSlots = countAvailableSlotsForPeriod(doctorId, from, to);
            if (availableSlots > 0) {
                availableSlotsByDoctor.put(doctorId, availableSlots);
            }
        }

        if (availableSlotsByDoctor.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay medicos con espacios disponibles en el rango solicitado.");
        }

        List<UUID> orderedDoctorIds = availableSlotsByDoctor.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();

        List<DoctorResponse> doctorsInfo = doctorConfigConsultPort.getDoctorInfoByIds(orderedDoctorIds);
        Map<UUID, DoctorResponse> doctorInfoById = new LinkedHashMap<>();
        for (DoctorResponse doctor : doctorsInfo) {
            doctorInfoById.put(doctor.id(), normalizeDoctorResponse(doctor));
        }

        Set<String> selectedSpecialties = new HashSet<>();
        List<DoctorResponse> result = new ArrayList<>();

        for (UUID doctorId : orderedDoctorIds) {
            DoctorResponse doctor = doctorInfoById.get(doctorId);
            if (doctor == null) {
                continue;
            }

            List<String> specialties = extractSpecialties(doctor.specialty());
            String specialtyToAssign = specialties.stream()
                    .filter(s -> !selectedSpecialties.contains(s))
                    .findFirst()
                    .orElse(null);

            if (specialtyToAssign == null) {
                continue;
            }

            selectedSpecialties.add(specialtyToAssign);
            result.add(new DoctorResponse(
                    specialtyToAssign,
                    doctor.id(),
                    doctor.name(),
                    doctor.laborEnd(),
                    doctor.workdays()
            ));
        }

        return result;
    }

    private int countAvailableSlotsForPeriod(UUID doctorId, LocalDate from, LocalDate to) {
        int total = 0;
        LocalDate current = from;

        while (!current.isAfter(to)) {
            try {
                if (current.getDayOfWeek().getValue() > 5) {
                    current = current.plusDays(1);
                    continue;
                }

                int daySlots = doctorConfigConsultPort.getSlotsByDoctor(doctorId, current).size();
                int occupied = appointmentRepository.findByDoctorIdAndDate(doctorId, current).size();
                total += Math.max(daySlots - occupied, 0);
            } catch (RuntimeException ignored) {
                // Si el medico no trabaja ese dia o no tiene horario, se ignora.
            }

            current = current.plusDays(1);
        }

        return total;
    }

    private DoctorResponse normalizeDoctorResponse(DoctorResponse doctor) {
        List<Integer> normalizedWorkdays = doctor.workdays() == null
                ? List.of()
                : doctor.workdays().stream()
                .filter(day -> day >= 1 && day <= 5)
                .distinct()
                .sorted()
                .toList();

        List<String> specialties = extractSpecialties(doctor.specialty());
        String normalizedSpecialty = specialties.isEmpty() ? "SIN_ESPECIALIDAD" : specialties.getFirst();

        return new DoctorResponse(
                normalizedSpecialty,
                doctor.id(),
                doctor.name(),
                doctor.laborEnd(),
                normalizedWorkdays
        );
    }

    private List<String> extractSpecialties(String rawSpecialty) {
        if (rawSpecialty == null || rawSpecialty.isBlank()) {
            return List.of();
        }

        String normalized = rawSpecialty
                .replace("[", "")
                .replace("]", "")
                .trim();

        if (normalized.isBlank()) {
            return List.of();
        }

        return List.of(normalized.split(","))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
