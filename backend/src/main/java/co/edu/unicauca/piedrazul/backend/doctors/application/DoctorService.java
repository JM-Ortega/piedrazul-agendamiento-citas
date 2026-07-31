package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorDetailedResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.*;

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
        Doctor doctor = new Doctor(
                personId,
                request.laborStart(),
                request.laborEnd(),
                request.bookingWindowWeeks(),
                false,
                request.appointmentInterval()
        );

        // Agregamos las especialidades
        for (SpecialtyCode code : request.specialty()) {
            Specialty specialty = specialtyRepository.findById(code)
                    .orElseThrow(() -> new DoctorInvalidSpecialty("Especialidad inválida: " + code));

            doctor.addSpecialty(specialty);
        }

        // Agregamos horarios si hay
        if (request.schedules() != null) {
            request.schedules().forEach(schedule ->
                    doctor.updateSchedule(
                            schedule.workday(),
                            schedule.startTime(),
                            schedule.endTime()
                    )
            );
            doctor.activateIfPossible();
        }

        doctorRepository.save(doctor);


        if (doctor.isStatus()) {
            personExternalService.ensureDoctorRole(personId);
        } else {
            personExternalService.revokeDoctorRole(personId);
        }
    }

    @Transactional
    @Override
    public void deleteDoctor(UUID personId) {
        if (personId == null) {
            throw new DoctorValidationException("El personId es obligatorio");
        }

        Doctor doctor = doctorRepository.findById(personId)
                .orElse(null);

        if (doctor == null) {
            return;
        }

        doctorRepository.delete(doctor);
    }

    @Transactional
    public void updateDoctorLaborDate(UUID idDoctor, LocalDate laborStart, LocalDate laborEnd) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        doctor.updateLaborPeriod(laborStart, laborEnd);

        doctorRepository.save(doctor);
    }


    // Actualizar el intervalo de atención de un doctor, valida que al menos un horario del doctor pueda acomodar el nuevo intervalo
    @Transactional
    public void updateDoctorAppointmentInterval(UUID idDoctor, int newAppointmentInterval) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        doctor.updateAppointmentInterval(newAppointmentInterval);

        doctorRepository.save(doctor);
    }

    // Habilitar medico
    @Transactional
    public void enableDoctor(UUID idDoctor) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        doctor.activate();

        doctorRepository.save(doctor);

        syncUserStatus(doctor);
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
        // Si da qeu si el front debe volver a enviar la peticino pero ahora con force true
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
            doctor.updateLaborPeriod(doctor.getLaborStart(), doctor.getLaborStart());
        } else {
            doctor.updateLaborPeriod(doctor.getLaborStart(), today);
        }

        // 4. Cambiar el estado del Doctor
        doctor.deactivate();

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

    public Page<DoctorDetailedResponse> findAllDoctorsDetailed(Pageable pageable) {

        Page<Doctor> doctors = doctorRepository.findAll(pageable);

        List<UUID> ids = doctors.getContent()
                .stream()
                .map(Doctor::getPersonId)
                .toList();

        Map<UUID, String> names = personExternalService.getPersonNames(ids);

        return doctors.map(doctor ->
                DoctorDetailedResponse.fromEntity(
                        doctor,
                        names.get(doctor.getPersonId())
                ));
    }

    public Doctor findByUserId(UUID keycloakId) {
        Doctor doctor = doctorRepository.findByPersonId(personExternalService.findPersonIdByUserId(keycloakId));
        if (doctor == null) {
            throw new DoctorNotFoundException("Doctor no encontrado para el usuario autenticado");
        }
        return doctor;
    }

    public List<Doctor> getDoctorBySpeciality(SpecialtyCode specialty) {
        return doctorRepository.findBySpecialtiesCode(specialty);
    }

    public List<Doctor> getDoctorsById(List<UUID> doctorIds) {
        return doctorRepository.findByPersonIdIn(doctorIds);
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
            personExternalService.ensureDoctorRole(doctor.getPersonId());
            return;
        }
        personExternalService.revokeDoctorRole(doctor.getPersonId());
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

        doctorRepository.save(doctor);
    }
}
