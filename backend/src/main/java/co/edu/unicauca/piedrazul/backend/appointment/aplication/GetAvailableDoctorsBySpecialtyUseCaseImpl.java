package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.NoDoctorsAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableDoctorsBySpecialtyUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.DoctorAvailabilityService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyDoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GetAvailableDoctorsBySpecialtyUseCaseImpl implements GetAvailableDoctorsBySpecialtyUseCase {
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentRepository appointmentRepository;
    private final DoctorAvailabilityService doctorAvailabilityService;

    public GetAvailableDoctorsBySpecialtyUseCaseImpl(
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentRepository appointmentRepository,
            DoctorAvailabilityService doctorAvailabilityService) {
        this.doctorConfigConsultPort  = doctorConfigConsultPort;
        this.appointmentRepository    = appointmentRepository;
        this.doctorAvailabilityService = doctorAvailabilityService;
    }

    @Override
    public List<SpecialtyDoctorResponse> getSpecialtiesWithDoctor() {

        LocalDate today = LocalDate.now();

        // 1. Trae médicos activos desde el módulo de doctores
        List<DoctorResponse> activeDoctors =
                doctorConfigConsultPort.getActiveDoctors();

        // 2. Filtra los que tienen disponibilidad desde hoy
        List<DoctorResponse> availableDoctors = activeDoctors.stream()
                .filter(doctor -> {
                    List<AppointmentTime> slots = doctorConfigConsultPort
                            .getSlotsByDoctor(doctor.id(), today);
                    int intervalMinutes = doctorConfigConsultPort
                            .getIntervalMinutesByDoctor(doctor.id());
                    List<Appointment> existingAppointments = appointmentRepository
                            .findByDoctorIdAndDate(doctor.id(), today);

                    return doctorAvailabilityService.hasAvailabilityFromToday(
                            doctor, slots, existingAppointments, intervalMinutes
                    );
                })
                .toList();

        // 3. Un médico por especialidad
        Map<String, DoctorResponse> onePerSpecialty = availableDoctors.stream()
                .collect(Collectors.toMap(
                        DoctorResponse::specialty,
                        doctor -> doctor,
                        (existing, replacement) -> existing
                ));

        // 4. Si no hay ninguno lanza excepción
        if (onePerSpecialty.isEmpty()) {
            throw new NoDoctorsAvailableException(
                    "No hay médicos disponibles para ninguna especialidad"
            );
        }

        // 5. Mapea a SpecialtyDoctorResponse
        return onePerSpecialty.values().stream()
                .map(doctor -> new SpecialtyDoctorResponse(
                        Specialty.valueOf(doctor.specialty()), // String -> enum
                        doctor.id(),
                        doctor.name(),
                        doctor.laborEnd(),
                        doctor.workdays()
                ))
                .toList();
    }
}
