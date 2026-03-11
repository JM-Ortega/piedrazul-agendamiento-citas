package co.edu.unicauca.piedrazul.backend.doctors.model.services;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.DoctorRepository;
import jakarta.transaction.Transactional;


import java.util.List;
import java.util.UUID;

public class DoctorService {
    private final DoctorRepository doctorRepository;

    public DoctorService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    // Crear un nuevo doctor
    @Transactional
    public Doctor createDoctor(Doctor doctor) {
        // Podrías añadir validaciones aquí (ej. si el idUser ya existe)
        return doctorRepository.save(doctor);
    }

    // Habilitar o deshabilitar al doctor (Cambiar status)
    @Transactional
    public Doctor setDoctorStatus(UUID idDoctor, boolean status) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));
        doctor.setStatus(status);
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
        return doctorRepository.findBySpecialty(specialty);
    }
}
