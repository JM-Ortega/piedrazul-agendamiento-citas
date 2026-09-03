package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorDetailedResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorShortResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.exception.*;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorProvisioningApi;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.SpecialtyRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
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

        int weeks = request.bookingWindowWeeks() != null ? request.bookingWindowWeeks() : 0;
        int interval = request.appointmentInterval() != null ? request.appointmentInterval() : 0;

        // Lo dejamos inactivo porque no tiene horarios
        Doctor doctor = new Doctor(
                personId,
                request.laborStart(),
                request.laborEnd(),
                weeks,
                false,
                interval
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
    public void updateDoctorInfo(UUID idDoctor, LocalDate laborStart, LocalDate laborEnd, int appointmentInterval,
                                 int bookingWindowWeeks) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        doctor.updateInfo(laborStart, laborEnd, appointmentInterval, bookingWindowWeeks);

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
    public void disableDoctor(UUID idDoctor) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        // Valida si el doctor aun tiene citas por atender
        if(appointmentExternalService.hasScheduledAppointments(idDoctor)) {
            throw new DoctorHasScheduledAppointments("No es posible deshabilitar el doctor porque aun tiene citas agendadas");
        }

        doctor.deactivate();

        syncUserStatus(doctor);

        doctorRepository.save(doctor);
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

    public Page<DoctorDetailedResponse> findAllDoctorsDetailed(Pageable pageable, String search) {

        if (search != null && !search.isBlank()) {
            return findBySearchTermDetailed(search, pageable);
        }

        return findAllDetailed(pageable);
    }

    public Doctor findByUserId(UUID keycloakId) {
        Doctor doctor = doctorRepository.findByPersonId(personExternalService.findPersonIdByUserId(keycloakId));
        if (doctor == null) {
            throw new DoctorNotFoundException("Doctor no encontrado para el usuario autenticado");
        }
        return doctor;
    }

    public List<SpecialtyCode> getAllSpecialties (){
        return Arrays.asList(SpecialtyCode.values());
    }

    public List<DoctorShortResponse> getNeuralDoctors(){
        List<Doctor> doctors = findAllDoctors();

        List<Doctor> neuralDoctors = doctors.stream()
                .filter(Doctor::isStatus)
                .filter(d -> d.getSpecialties().stream()
                        .anyMatch(s -> s.getCode() == SpecialtyCode.TERAPIA_NEURAL))
                .toList();

        Map<UUID, String> names = personExternalService.getPersonNames(
                neuralDoctors.stream()
                        .map(Doctor::getPersonId)
                        .toList()
        );

        return neuralDoctors.stream()
                .map(d -> DoctorShortResponse.fromEntity(
                        d,
                        names.get(d.getPersonId())
                ))
                .toList();
    }

    public List<DoctorResponse> getActiveDoctors(UUID patientId) {
        List<Doctor> doctors = findAllDoctors();

        boolean isNewPatient = patientId != null && appointmentExternalService.isNewPatient(patientId);

        if (isNewPatient) {
            doctors = doctors.stream()
                    .filter(d -> d.getSpecialties().stream()
                            .anyMatch(s -> s.getCode() == SpecialtyCode.TERAPIA_NEURAL))
                    .toList();
        }

        if (doctors.isEmpty()) {
            throw new NoAvailableDoctorsException("No hay médicos activos disponibles");
        }

        List<Doctor> activeDoctors = doctors.stream()
                .filter(Doctor::isStatus)
                .toList();

        Map<UUID, String> names = personExternalService.getPersonNames(
                activeDoctors.stream()
                        .map(Doctor::getPersonId)
                        .toList()
        );

        return activeDoctors.stream()
                .map(d -> DoctorResponse.fromEntity(
                        d,
                        names.get(d.getPersonId()),
                        isNewPatient
                ))
                .toList();
    }

    public List<Doctor> findAllDoctors() {
        return doctorRepository.findAll();
    }

    // PRIVATE
    private void syncUserStatus(Doctor doctor) {
        if (doctor.isStatus()) {
            personExternalService.ensureDoctorRole(doctor.getPersonId());
            return;
        }
        personExternalService.revokeDoctorRole(doctor.getPersonId());
    }

    private Page<DoctorDetailedResponse> findBySearchTermDetailed(String search, Pageable pageable) {
        List<UUID> allDoctorIds = doctorRepository.findAll().stream()
                .map(Doctor::getPersonId)
                .toList();

        if (allDoctorIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // findByIdsAndNameOrIdentificationContaining no admite Sort personalizado.
        Pageable unsortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        Page<PersonSummary> personPage = personExternalService
                .findByIdsAndNameOrIdentificationContaining(allDoctorIds, search, unsortedPageable);

        List<UUID> matchedIds = personPage.getContent().stream()
                .map(PersonSummary::id) // ajustá al getter/record real de PersonSummary
                .toList();

        Map<UUID, Doctor> doctorsById = doctorRepository.findAllById(matchedIds).stream()
                .collect(Collectors.toMap(Doctor::getPersonId, d -> d));

        // Se respeta el orden que devuelve el servicio de persona
        List<DoctorDetailedResponse> content = personPage.getContent().stream()
                .map(person -> {
                    Doctor doctor = doctorsById.get(person.id());
                    return doctor == null ? null : DoctorDetailedResponse.fromEntity(doctor, person.firstName()+" "+person.lastName());
                })
                .filter(Objects::nonNull)
                .toList();

        return new PageImpl<>(content, pageable, personPage.getTotalElements());
    }

    private Page<DoctorDetailedResponse> findAllDetailed(Pageable pageable) {
        boolean isSortingByName = pageable.getSort().stream()
                .anyMatch(order -> order.getProperty().equalsIgnoreCase("name"));

        if (isSortingByName) {
            List<Doctor> allDoctors = doctorRepository.findAll();

            List<UUID> allIds = allDoctors.stream().map(Doctor::getPersonId).toList();
            Map<UUID, String> allNames = personExternalService.getPersonNames(allIds);

            List<DoctorDetailedResponse> allResponses = allDoctors.stream()
                    .map(doctor -> DoctorDetailedResponse.fromEntity(doctor, allNames.get(doctor.getPersonId())))
                    .collect(Collectors.toList());

            boolean isDescending = pageable.getSort().getOrderFor("name").isDescending();
            Comparator<DoctorDetailedResponse> nameComparator = Comparator.comparing(
                    d -> d.name() == null ? "" : d.name(),
                    String.CASE_INSENSITIVE_ORDER
            );
            if (isDescending) {
                nameComparator = nameComparator.reversed();
            }
            allResponses.sort(nameComparator);

            int total = allResponses.size();
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), total);

            if (start >= total) {
                return new PageImpl<>(List.of(), pageable, total);
            }

            return new PageImpl<>(allResponses.subList(start, end), pageable, total);
        } else {
            Page<Doctor> doctors = doctorRepository.findAll(pageable);

            List<UUID> ids = doctors.getContent().stream().map(Doctor::getPersonId).toList();
            Map<UUID, String> names = personExternalService.getPersonNames(ids);

            return doctors.map(doctor -> DoctorDetailedResponse.fromEntity(doctor, names.get(doctor.getPersonId())));
        }
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
}
