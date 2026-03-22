package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.SpecialtyDoctor;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;


import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // 1. Mapeo de DTO a Entidad
        Doctor doctor = new Doctor();
        doctor.setIdUser(request.idUser());
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setIdentification(request.identification());
        doctor.setSpecialty(request.specialty());
        doctor.setLaborStart(request.laborStart());
        doctor.setLaborEnd(request.laborEnd());
        doctor.setAppointmentInterval(request.appointmentInterval());
        doctor.setSchedules(request.schedules());
        //Validamos si el estado del medico
        doctor.setStatus(calculateActiveStatus(request.laborStart(), request.laborEnd()));

        // 2. Persistencia
        Doctor savedDoctor = doctorRepository.save(doctor);

        // 3. Crear el usuario
        userModuleApi.createDoctorUser(request.identification());

        // 4. Retornar un DTO de respuesta
        return DoctorResponse.fromEntity(savedDoctor);
    }

    private boolean calculateActiveStatus(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
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
    }

    // Habilitar medico
    @Transactional
    public DoctorResponse enableDoctor(UUID idDoctor, LocalDate newStart, LocalDate newEnd) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor no encontrado"));

        // 1. Actualizamos el periodo laboral
        doctor.setLaborStart(newStart);
        doctor.setLaborEnd(newEnd);

        // 2. Cambiamos el estado (aquí forzamos true porque es la acción de habilitar)
        doctor.setStatus(true);

        // 3. Reactivamos el usuario para que pueda loguearse
        userModuleApi.activateUser(doctor.getIdUser());

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
        userModuleApi.deactivateUser(doctor.getIdUser());

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
        return doctorRepository.findBySpecialty(Collections.singletonList(specialty));
    }

    public List<Specialty> getSpecialties (){
        return doctorRepository.findAllDistinctSpecialtiesByActiveDoctors();
    }

    public List<SpecialtyDoctor> getSpecialtiesWithDoctor() {
        List<Doctor> availableDoctors = doctorRepository.findAvailableDoctorsForSpecialtyAssignment(LocalDate.now());
        if (availableDoctors.isEmpty()) {
            throw new EntityNotFoundException("No hay médicos disponibles para las especialidades");
        }

        Map<Specialty, SpecialtyDoctor> specialtiesWithDoctor = new LinkedHashMap<>();

        for (Doctor doctor : availableDoctors) {
            for (Specialty specialty : doctor.getSpecialty()) {
                specialtiesWithDoctor.putIfAbsent(specialty, SpecialtyDoctor.from(specialty, doctor));
            }
        }

        if (specialtiesWithDoctor.isEmpty()) {
            throw new EntityNotFoundException("No hay médicos disponibles para las especialidades");
        }

        return specialtiesWithDoctor.values().stream().toList();
    }
}
