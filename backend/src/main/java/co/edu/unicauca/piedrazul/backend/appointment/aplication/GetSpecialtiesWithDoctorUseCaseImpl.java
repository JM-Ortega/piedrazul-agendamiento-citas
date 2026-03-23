package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.NoAvailableDoctorsException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetSpecialtiesWithDoctorUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger logger = LoggerFactory.getLogger(GetSpecialtiesWithDoctorUseCaseImpl.class);
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
        logger.info("=== INICIANDO BÚSQUEDA DE ESPECIALIDADES CON DOCTORES ===");
        logger.info("Rango de fechas: {} a {}", from, to);

        List<UUID> activeDoctorIds = doctorConfigConsultPort.getActiveDoctorIds();
        logger.info("Médicos activos encontrados: {}", activeDoctorIds.size());
        if (activeDoctorIds.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay medicos activos con disponibilidad.");
        }

        Map<UUID, Integer> availableSlotsByDoctor = new HashMap<>();

        for (UUID doctorId : activeDoctorIds) {
            int availableSlots = countAvailableSlotsForPeriod(doctorId, from, to);
            logger.info("✓ Doctor {} tiene {} slots disponibles", doctorId, availableSlots);
            if (availableSlots > 0) {
                availableSlotsByDoctor.put(doctorId, availableSlots);
            } else {
                logger.info("✗ Doctor {} DESCARTADO: sin slots disponibles", doctorId);
            }
        }
        logger.info("Doctores con disponibilidad: {}", availableSlotsByDoctor.size());

        if (availableSlotsByDoctor.isEmpty()) {
            logger.warn("No hay médicos con espacios disponibles");
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
                logger.warn("Doctor {} no encontrado en info", doctorId);
                continue;
            }

            List<String> specialties = extractSpecialties(doctor.specialty());
            logger.info("Doctor {} ({}): especialidades disponibles: {}", doctorId, doctor.name(), specialties);
            String specialtyToAssign = specialties.stream()
                    .filter(s -> !selectedSpecialties.contains(s))
                    .findFirst()
                    .orElse(null);

            if (specialtyToAssign == null) {
                logger.info("Doctor {} ({}) DESCARTADO: todas sus especialidades ya están asignadas. Especialidades ya seleccionadas: {}", 
                    doctorId, doctor.name(), selectedSpecialties);
                continue;
            }

            selectedSpecialties.add(specialtyToAssign);
            logger.info("✓ Doctor {} ({}) SELECCIONADO para especialidad: {}", doctorId, doctor.name(), specialtyToAssign);
            result.add(new DoctorResponse(
                    specialtyToAssign,
                    doctor.id(),
                    doctor.name(),
                    doctor.laborEnd(),
                    doctor.workdays()
            ));
        }

        logger.info("=== RESULTADO FINAL: {} médicos seleccionados ===", result.size());
        return result;
    }

    private int countAvailableSlotsForPeriod(UUID doctorId, LocalDate from, LocalDate to) {
        logger.info("  [CONTANDO SLOTS] Doctor {} - Período: {} a {}", doctorId, from, to);
        
        int total = 0;
        LocalDate current = from;
        int daysChecked = 0;
        int daysWithSlots = 0;

        while (!current.isAfter(to)) {
            try {
                if (current.getDayOfWeek().getValue() > 5) {
                    // Fin de semana
                    logger.info("    {} - FIN DE SEMANA (día {})", current, current.getDayOfWeek().getValue());
                    current = current.plusDays(1);
                    continue;
                }

                daysChecked++;
                int daySlots = doctorConfigConsultPort.getSlotsByDoctor(doctorId, current).size();
                int occupied = appointmentRepository.findByDoctorIdAndDate(doctorId, current).size();
                int available = Math.max(daySlots - occupied, 0);
                total += available;
                
                logger.info("    {} - Doctor {}: {} configurados, {} ocupados, {} disponibles", 
                    current, doctorId, daySlots, occupied, available);
                    
                if (daySlots > 0) {
                    daysWithSlots++;
                }
            } catch (RuntimeException e) {
                logger.info("    {} - Doctor {} ERROR al consultar horario: {}", current, doctorId, e.getMessage());
            }

            current = current.plusDays(1);
        }
        
        logger.info("  [RESUMEN] Doctor {}: {} días laborales chequeados, {} con slots, {} TOTAL DISPONIBLES", 
            doctorId, daysChecked, daysWithSlots, total);

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
