package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorInvalidSpecialty;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorValidationException;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.SpecialtyRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class DoctorService implements DoctorProvisioningApi {
    private final DoctorRepository doctorRepository;
    private final AppointmentExternalService appointmentExternalService;
    private final PersonExternalService personExternalService;
    private final SpecialtyRepository specialtyRepository;

    public DoctorService(DoctorRepository doctorRepository, AppointmentExternalService appointmentExternalService,
    PersonExternalService personExternalService, SpecialtyRepository specialtyRepository)
    {
        this.doctorRepository = doctorRepository;
        this.appointmentExternalService = appointmentExternalService;
        this.personExternalService = personExternalService;
        this.specialtyRepository = specialtyRepository;
    }

    @Transactional
    @Override
    public void createDoctor(UUID personId, CreateDoctorRequest request) {
        validateLaborDateRange(request.laborStart(), request.laborEnd());

        // Lo dejamos inactivo porque no tiene horarios
        Doctor doctor = new Doctor(personId, request.laborStart(), request.laborEnd(), request.bookingWindowWeeks(),
                false, request.appointmentInterval());

        // Agregamos las especialidades
        Set<Specialty> specialties = new HashSet<>();
        for (SpecialtyCode code : request.specialty()) {
            Specialty specialty = specialtyRepository.findById(code)
                    .orElseThrow(() -> new DoctorInvalidSpecialty("Especialidad inválida: " + code));
            specialties.add(specialty);
        }

        doctor.setSpecialties(specialties);


        // Lo dejamos inactivo porque no tiene horarios
        personExternalService.deactivateUser(personId);


        // Agregamos los horarios, si hay
        if(request.schedules() != null){
            Set<Schedule> schedules = request.schedules().stream()
                    .map(s -> new Schedule(
                            doctor,
                            s.startTime(),
                            s.endTime(),
                            s.workday()
                    )).collect(Collectors.toSet());

            doctor.setSchedules(schedules);

            // Como si hay horaios validamos si el doctor debe estar activo o no
            doctor.setStatus(calculateActiveStatus(request.laborStart(), request.laborEnd()));
        }

        // Persistimos
        Doctor savedDoctor = doctorRepository.save(doctor);

        // Activamos el usuario si el doctor está activo
        if (savedDoctor.isStatus()) {
            personExternalService.activateUser(personId);
        }
    }

    private boolean calculateActiveStatus(LocalDate start, LocalDate end) {
        if (start == null) {
            throw new DoctorValidationException("La fecha de inicio es obligatoria");
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
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

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
            throw new DoctorValidationException("La fecha de inicio es obligatoria");
        }

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

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
            throw new DoctorValidationException("La fecha de finalización es obligatoria");
        }

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

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
            throw new DoctorValidationException("El intervalo de atención debe ser mayor a 0");
        }

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        // Evita intervalos imposibles para todos los horarios existentes.
        Set<Schedule> schedules = doctor.getSchedules() == null ? new HashSet<>() : doctor.getSchedules();
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
    public void enableDoctor(UUID idDoctor, LocalDate newStart, LocalDate newEnd) {
        validateLaborDateRange(newStart, newEnd);

        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        // 1. Actualizamos el periodo laboral
        doctor.setLaborStart(newStart);
        doctor.setLaborEnd(newEnd);

        // 2. Cambiamos el estado (aquí forzamos true porque es la acción de habilitar)
        doctor.setStatus(true);

        // 3. Reactivamos el usuario para que pueda loguearse
        syncUserStatus(doctor);

        // 4. Guardamos y retornamos el DTO actualizado
        doctorRepository.save(doctor);
    }

    //Deshabilitar medico
    @Transactional
    public void disableDoctor(UUID idDoctor, boolean force) {
        // 1. Buscar al doctor
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

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
        doctorRepository.save(doctor);
    }

    public List<Doctor> findAllDoctors() {
        return doctorRepository.findAll();
    }

    public Doctor getDoctorById(UUID idDoctor) {
        return doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));
    }

    public Doctor findByUserId(UUID keycloakId) {
        Doctor doctor = doctorRepository.findByIdUser(keycloakId);
        if (doctor == null) {
            throw new DoctorNotFoundException("Doctor no encontrado para el usuario autenticado");
        }
        return doctor;
    }

    public List<Doctor> getDoctorBySpeciality(SpecialtyCode specialty) {
        return doctorRepository.findBySpecialtyContaining(specialty);
    }

    public List<Doctor> getDoctorsById(List<UUID> doctorIds) {
        return doctorRepository.findByIdDoctorIn(doctorIds);
    }

    public List<SpecialtyCode> getSpecialties(UUID idPatient) {

        List<SpecialtyCode> activeSpecialties = doctorRepository
                .findAllDistinctSpecialtyCodesByActiveDoctors()
                .stream()
                .map(SpecialtyCode::valueOf)
                .toList();

        if (appointmentExternalService.isNewPatient(idPatient)) {
            return activeSpecialties.contains(SpecialtyCode.MEDICINA_GENERAL)
                    ? List.of(SpecialtyCode.MEDICINA_GENERAL)
                    : Collections.emptyList();
        }

        return activeSpecialties;
    }

    public List<SpecialtyCode> getAllSpecialties (){
        return Arrays.asList(SpecialtyCode.values());
    }

    private void syncUserStatus(Doctor doctor) {
        if (doctor.isStatus()) {
            personExternalService.activateUser(doctor.getPersonId());
            return;
        }
        personExternalService.deactivateUser(doctor.getPersonId());
    }

    private void validateLaborDateRange(LocalDate laborStart, LocalDate laborEnd) {
        if (laborStart == null) {
            throw new DoctorValidationException("La fecha de inicio es obligatoria");
        }
        if (laborEnd == null) {
            throw new DoctorValidationException("La fecha de finalización es obligatoria");
        }
        if (laborEnd.isBefore(laborStart)) {
            throw new DateConflictException("La fecha de finalización no puede ser anterior a la fecha de inicio");
        }
    }

    @Transactional
    public void changeSpecialties(UUID doctorId, List<SpecialtyCode> codigosNuevos) {

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado: " + doctorId));

        Set<Specialty> nuevas = new HashSet<>(specialtyRepository.findAllById(codigosNuevos));
        if (nuevas.size() != new HashSet<>(codigosNuevos).size()) {
            throw new IllegalArgumentException("Alguna especialidad enviada no existe en el catálogo");
        }

        Set<Specialty> actuales = doctor.getSpecialties();
        actuales.removeIf(s -> !nuevas.contains(s)); // quita las que ya no vienen
        actuales.addAll(nuevas); // agrega las nuevas
    }
}
