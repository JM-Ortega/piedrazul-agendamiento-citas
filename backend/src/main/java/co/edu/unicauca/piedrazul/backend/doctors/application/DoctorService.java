package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

public class DoctorService {
    private final DoctorRepository doctorRepository;
    private final UserModuleApi userModuleApi;

    public DoctorService(DoctorRepository doctorRepository, UserModuleApi userModuleApi) {
        this.doctorRepository = doctorRepository;
        this.userModuleApi = userModuleApi;
    }

    // Crear un nuevo doctor
    @Transactional
    public DoctorResponse createDoctor(CreateDoctorRequest request) {
        validateLaborDateRange(request.laborStart(), request.laborEnd());

        // 1. Mapeo de DTO a Entidad
        Doctor doctor = new Doctor();
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setIdentification(request.identification());
        doctor.setSpecialty(request.specialty());
        doctor.setLaborStart(request.laborStart());
        doctor.setLaborEnd(request.laborEnd());
        doctor.setAppointmentInterval(request.appointmentInterval());
        doctor.setStatus(calculateActiveStatus(request.laborStart(), request.laborEnd()));

        List<Schedule> schedules = request.schedules().stream()
                .map(s -> new Schedule(
                        doctor,
                        s.startTime(),
                        s.endTime(),
                        s.workday()
                ))
                .toList();

        doctor.setSchedules(schedules);

        // 2. Crear el usuario
        doctor.setIdUser(userModuleApi.getOrCreateDoctorUser(
                request.identification(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password()
        ));

        // 3. Deshabilitar el usuario si el doctor no está activo
        if (!doctor.isStatus())
            userModuleApi.deactivateUser(doctor.getIdUser());

        // 4. Persistencia
        Doctor savedDoctor = doctorRepository.save(doctor);

        // 5. Retornar un DTO de respuesta
        return DoctorResponse.fromEntity(savedDoctor);
    }

    private boolean calculateActiveStatus(LocalDate start, LocalDate end) {
        if (start == null) {
            throw new IllegalArgumentException("La fecha de inicio es obligatoria");
        }

        LocalDate today = LocalDate.now();
        if (end == null) {
            return !today.isBefore(start);
        }

        return !today.isBefore(start) && !today.isAfter(end);
    }

    @Transactional
    //Metodo para validar si el medico esta activo o no usarlo cada vez que ingrese el medico
    public void updateDoctorStatus(UUID idDoctor) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        boolean isActive = calculateActiveStatus(doctor.getLaborStart(), doctor.getLaborEnd());

        // Solo guardamos si el estado cambió (optimización de JPA)
        if (doctor.isStatus() != isActive) {
            doctor.setStatus(isActive);
            doctorRepository.save(doctor);
        }

        syncUserStatus(doctor);
    }

    // Actualizar la fecha de inicio laboral de un doctor
    @Transactional
    public void updateDoctorLaborStart(UUID idDoctor, LocalDate newLaborStart) {
        if (newLaborStart == null) {
            throw new IllegalArgumentException("La fecha de inicio es obligatoria");
        }

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor no encontrado"));

        if (doctor.getLaborEnd() != null && newLaborStart.isAfter(doctor.getLaborEnd())) {
            throw new DateConflictException("La fecha de inicio no puede ser posterior a la fecha de finalización");
        }

        doctor.setLaborStart(newLaborStart);
        doctor.setStatus(calculateActiveStatus(doctor.getLaborStart(), doctor.getLaborEnd()));
        syncUserStatus(doctor);

        doctorRepository.save(doctor);
    }

    // Actualizar la fecha de finalización laboral de un doctor
    @Transactional
    public void updateDoctorLaborEnd(UUID idDoctor, LocalDate newLaborEnd) {
        if (newLaborEnd == null) {
            throw new IllegalArgumentException("La fecha de finalización es obligatoria");
        }

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor no encontrado"));

        if (doctor.getLaborStart() == null) {
            throw new DateConflictException("No se puede actualizar la fecha de finalización porque el médico no tiene fecha de inicio registrada");
        }

        if (newLaborEnd.isBefore(doctor.getLaborStart())) {
            throw new DateConflictException("La fecha de finalización no puede ser anterior a la fecha de inicio");
        }

        doctor.setLaborEnd(newLaborEnd);
        doctor.setStatus(calculateActiveStatus(doctor.getLaborStart(), doctor.getLaborEnd()));
        syncUserStatus(doctor);

        doctorRepository.save(doctor);
    }

    // Actualizar el intervalo de atención de un doctor, valida que al menos un horario del doctor pueda acomodar el nuevo intervalo
    @Transactional
    public void updateDoctorAppointmentInterval(UUID idDoctor, int newAppointmentInterval) {
        if (newAppointmentInterval <= 0) {
            throw new IllegalArgumentException("El intervalo de atención debe ser mayor a 0");
        }

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor no encontrado"));

        // Evita intervalos imposibles para todos los horarios existentes.
        List<Schedule> schedules = doctor.getSchedules() == null ? List.of() : doctor.getSchedules();
        if (!schedules.isEmpty()) {
            boolean fitsAtLeastOneSchedule = schedules.stream().anyMatch(schedule -> {
                long duration = ChronoUnit.MINUTES.between(schedule.getStartTime(), schedule.getEndTime());
                return duration >= newAppointmentInterval;
            });

            if (!fitsAtLeastOneSchedule) {
                throw new IllegalArgumentException("El nuevo intervalo es mayor a la duración de todos los horarios del médico");
            }
        }

        doctor.setAppointmentInterval(newAppointmentInterval);
        doctorRepository.save(doctor);
    }

    // Habilitar medico
    @Transactional
    public DoctorResponse enableDoctor(UUID idDoctor, LocalDate newStart, LocalDate newEnd) {
        validateLaborDateRange(newStart, newEnd);

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor no encontrado"));

        // 1. Actualizamos el periodo laboral
        doctor.setLaborStart(newStart);
        doctor.setLaborEnd(newEnd);

        // 2. Cambiamos el estado (aquí forzamos true porque es la acción de habilitar)
        doctor.setStatus(true);

        // 3. Reactivamos el usuario para que pueda loguearse
        syncUserStatus(doctor);

        // 4. Guardamos y retornamos el DTO actualizado
        Doctor savedDoctor = doctorRepository.save(doctor);
        return DoctorResponse.fromEntity(savedDoctor);
    }

    //Deshabilitar medico
    @Transactional
    public DoctorResponse disableDoctor(UUID idDoctor, boolean force) {
        // 1. Buscar al doctor
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor no encontrado"));

        LocalDate today = LocalDate.now();

        // 2. Validación
        // Si hoy es antes de que siquiera empiece a trabajar, lanzamos la advertencia
        if (today.isBefore(doctor.getLaborStart()) && !force) {
            throw new DateConflictException(
                    "El doctor aún no ha iniciado labores (Inicia: " + doctor.getLaborStart() +
                            "). ¿Está seguro de que desea cancelar su contrato ahora?"
            );
        }

        // 3. Ajustar la fecha de fin
        // Si hoy es antes del inicio (por el force), igualamos fin al inicio.
        // De lo contrario, el fin es hoy.
        if (today.isBefore(doctor.getLaborStart())) {
            doctor.setLaborEnd(doctor.getLaborStart());
        } else {
            doctor.setLaborEnd(today);
        }

        // 4. Cambiar el estado del Doctor
        doctor.setStatus(false);

        // 5. Desactivamos el usuario para que no pueda loguearse
        syncUserStatus(doctor);

        // 6. Persistir cambios
        Doctor updatedDoctor = doctorRepository.save(doctor);

        return DoctorResponse.fromEntity(updatedDoctor);
    }

    public List<Doctor> findAllDoctors() {
        return doctorRepository.findAll();
    }

    public Doctor getDoctorById(UUID idDoctor) {
        return doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
    }

    public List<Doctor> getDoctorBySpeciality(Specialty specialty) {
        return doctorRepository.findBySpecialtyContaining(specialty);
    }

    public List<Specialty> getSpecialties (){
        return doctorRepository.findAllDistinctSpecialtiesByActiveDoctors();
    }

    private void syncUserStatus(Doctor doctor) {
        if (doctor.isStatus()) {
            userModuleApi.activateUser(doctor.getIdUser());
            return;
        }
        userModuleApi.deactivateUser(doctor.getIdUser());
    }

    private void validateLaborDateRange(LocalDate laborStart, LocalDate laborEnd) {
        if (laborStart == null) {
            throw new IllegalArgumentException("La fecha de inicio es obligatoria");
        }
        if (laborEnd == null) {
            throw new IllegalArgumentException("La fecha de finalización es obligatoria");
        }
        if (laborEnd.isBefore(laborStart)) {
            throw new DateConflictException("La fecha de finalización no puede ser anterior a la fecha de inicio");
        }
    }
}
