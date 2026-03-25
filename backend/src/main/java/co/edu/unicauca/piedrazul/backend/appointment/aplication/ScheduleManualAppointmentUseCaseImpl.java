package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.PatientRegistrationData;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ScheduleManualAppointmentUseCaseImpl implements ScheduleManualAppointmentUseCase {
    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;
    private final PatientConsultPort patientConsultPort;

    public ScheduleManualAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            PatientConsultPort patientConsultPort) {
        this.appointmentRepository   = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService      = appointmentService;
        this.patientConsultPort = patientConsultPort;
    }

    // Agendador crea la cita manualmente
    @Override
    public Appointment scheduleManual(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone,
            UUID idDoctor,
            Specialty specialty,
            LocalDate date,
            AppointmentTime startTime) {

        // 1. Obtiene la configuración del médico a través del puerto de salida
        int intervalMinutes = doctorConfigConsultPort
                .getIntervalMinutesByDoctor(idDoctor);
        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);

        // 2. Obtiene las citas existentes del médico ese día a través del puerto de salida
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndDate(idDoctor, date);

        // 3. Buscar paciente por documento
        Optional<PatientSnapshot> existingPatient =
                patientConsultPort.findByDocumentNumber(documentNumber);

        UUID idPatient = null;
        PatientInfo finalPatientInfo;

        if (existingPatient.isPresent()) {
            // ya existe
            idPatient = existingPatient.get().idPatient();
            finalPatientInfo = existingPatient.get().patientInfo();
        } else {
            // No existe → ahora sí se construye y valida el PatientInfo
            PatientInfo patientInfo = PatientInfo.of(
                    documentType,
                    documentNumber,
                    firstName,
                    lastName,
                    phone,
                    gender,
                    birthDate,
                    email,
                    guardianPhone
            );

            finalPatientInfo = patientInfo;

            idPatient = patientConsultPort.createPatient(
                    new PatientRegistrationData(
                            documentType,
                            documentNumber,
                            firstName,
                            lastName,
                            phone,
                            email,
                            gender,
                            birthDate,
                            guardianPhone
                    )
            );
        }

        validateUniqueScheduledAppointmentBySpecialty(idPatient, specialty);
        validateNoTimeConflictForPatient(idPatient, date, startTime);

        // 4. Delega la lógica de negocio al servicio de dominio
        Appointment appointment = appointmentService.scheduleManual(
                doctorName,
                idPatient,
                finalPatientInfo,
                idDoctor,
                finalPatientInfo.getFirstName() + " " + finalPatientInfo.getLastName(),
                specialty,
                date,
                startTime,
                intervalMinutes,
                existingAppointments
        );

        appointmentRepository.save(appointment);

        return appointment;
    }

    private void validateUniqueScheduledAppointmentBySpecialty(UUID idPatient, Specialty specialty) {
        boolean hasScheduledInSameSpecialty = appointmentRepository.findByPatientId(idPatient)
                .stream()
                .anyMatch(appointment -> appointment.getSpecialty() == specialty
                        && appointment.getAppointmentState() == AppointmentState.AGENDADA);

        if (hasScheduledInSameSpecialty) {
            throw new PatientAlreadyScheduledInSpecialtyException(
                    "El paciente ya tiene una cita AGENDADA para la especialidad " + specialty
            );
        }
    }

    private void validateNoTimeConflictForPatient(UUID idPatient, LocalDate date, AppointmentTime startTime) {
        boolean hasTimeConflict = appointmentRepository.findByPatientIdAndDate(idPatient, date)
                .stream()
                .anyMatch(appointment -> appointment.getStartTime().equals(startTime)
                        && appointment.getAppointmentState().isActive());

        if (hasTimeConflict) {
            throw new PatientScheduleTimeConflictException(
                    "El paciente ya tiene una cita activa para la fecha " + date + " a las " + startTime.getTime()
            );
        }
    }

}