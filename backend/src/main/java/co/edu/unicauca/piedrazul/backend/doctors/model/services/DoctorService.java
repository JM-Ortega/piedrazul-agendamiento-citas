package co.edu.unicauca.piedrazul.backend.doctors.model.services;

import co.edu.unicauca.piedrazul.backend.doctors.controller.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.controller.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.model.exceptions.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.DoctorRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;


import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class DoctorService {
    private final DoctorRepository doctorRepository;

    public DoctorService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    // Crear un nuevo doctor
    @Transactional
    public DoctorResponse createDoctor(CreateDoctorRequest request) {
        // Mapeo de DTO a Entidad
        Doctor doctor = new Doctor();
        doctor.setIdUser(request.idUser());
        doctor.setFirstName(request.firstName());
        doctor.setLastName(request.lastName());
        doctor.setSpecialty(request.specialty());
        doctor.setLaborStart(request.laborStart());
        doctor.setLaborEnd(request.laborEnd());
        doctor.setAppointmentInterval(request.appointmentInterval());
        doctor.setSchedulableWeeks(request.schedulableWeeks());
        doctor.setSchedules(request.schedules());
        //Validamos si el estado del medico
        doctor.setStatus(calculateActiveStatus(request.laborStart(), request.laborEnd()));

        // Persistencia
        Doctor savedDoctor = doctorRepository.save(doctor);

        // 4. Retornar un DTO de respuesta (nunca la entidad @Entity)
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

        // !! Llamada al módulo de usuarios para habilitar el usuario del medico
        // 3. Reactivamos el usuario para que pueda loguearse
        //userService.enableUser(doctor.getIdUser());

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

        // !! Llamada al módulo de usuarios para habilitar el usuario del medico
        // 5. Desactivamos el usuario para que no pueda loguearse
        //userService.disableUser(doctor.getIdUser());

        // 6. Persistir cambios
        Doctor updatedDoctor = doctorRepository.save(doctor);

        return DoctorResponse.fromEntity(updatedDoctor);
    }

    // Deshabilitar al doctor (Cambiar status)
    @Transactional
    public Doctor setDoctorStatus(UUID idDoctor, boolean status, boolean force) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new EntityNotFoundException("Doctor not found"));

        LocalDate today = LocalDate.now();

        // Si se intenta deshabilitar y hoy es antes de la fecha de inicio
        if (!status && today.isBefore(doctor.getLaborStart()) && !force) {
            throw new DateConflictException(
                    "La fecha de fin quedará antes de la fecha de inicio. ¿Desea continuar?"
            );
        }

        doctor.setStatus(status);

        if (!status) {
            doctor.setLaborEnd(today);
        }

        return doctorRepository.save(doctor);
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
}
