package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientRegistryPort;
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
    private final PatientRegistryPort patientRegistryPort;

    public ScheduleManualAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            PatientConsultPort patientConsultPort,
            PatientRegistryPort patientRegistryPort) {
        this.appointmentRepository   = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService      = appointmentService;
        this.patientConsultPort = patientConsultPort;
        this.patientRegistryPort = patientRegistryPort;
    }


    /*
    // Agendador crea la cita manualmente
    @Override
    public Appointment scheduleManual(PatientInfo patientInfo, UUID idDoctor, Specialty specialty, LocalDate date, AppointmentTime startTime) {
        // 1. Obtiene la configuración del médico a través del puerto de salida
        int intervalMinutes = doctorConfigConsultPort
                .getIntervalMinutesByDoctor(idDoctor);
        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);

        // 2. Obtiene las citas existentes del médico ese día a través del puerto de salida
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndDate(idDoctor, date);

        // 3. Delega la lógica de negocio al servicio de dominio
        Appointment appointment = appointmentService.scheduleManual(
                doctorName, patientInfo, idDoctor, specialty,
                date, startTime, intervalMinutes, existingAppointments
        );

        // 4. Persiste a través del puerto de salida
        appointmentRepository.save(appointment);

        return appointment;
    }
*/
    // Agendador crea la cita manualmente
    @Override
    public Appointment scheduleManual(PatientInfo patientInfo,
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
                patientConsultPort.findByDocumentNumber(patientInfo.getDocumentNumber());
        UUID idPatient = null;
        PatientInfo finalPatientInfo;

        if (existingPatient.isPresent()) {
            // ya existe
            idPatient = existingPatient.get().idPatient();
            finalPatientInfo = existingPatient.get().patientInfo();
        } else {
            // No existe
            finalPatientInfo = patientInfo;

            idPatient = patientRegistryPort.createPatient(
                    new PatientRegistrationData(
                            finalPatientInfo.getDocumentType(),
                            finalPatientInfo.getDocumentNumber(),
                            finalPatientInfo.getFirstName(),
                            finalPatientInfo.getLastName(),
                            finalPatientInfo.getPhone(),
                            finalPatientInfo.getEmail(),
                            finalPatientInfo.getGender(),
                            finalPatientInfo.getBirthDate(),
                            finalPatientInfo.getGuardianPhone()
                    )
            );
        }

        // 4. Delega la lógica de negocio al servicio de dominio
        Appointment appointment = appointmentService.scheduleManual(
                doctorName,
                idPatient,
                finalPatientInfo,
                idDoctor,
                specialty,
                date,
                startTime,
                intervalMinutes,
                existingAppointments
        );

        appointmentRepository.save(appointment);

        return appointment;

    }

}