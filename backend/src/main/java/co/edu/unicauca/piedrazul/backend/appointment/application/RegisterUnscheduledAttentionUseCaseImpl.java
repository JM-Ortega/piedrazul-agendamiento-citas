package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.RegisterUnscheduledAttentionUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.exception.DoctorSpecialtyMismatchException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.UUID;

public class RegisterUnscheduledAttentionUseCaseImpl implements RegisterUnscheduledAttentionUseCase {

    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final ClinicalHistoryPort clinicalHistoryPort;
    private final ManualPatientResolutionStrategy manualPatientResolutionStrategy;
    private final AppointmentRepository appointmentRepository;

    public RegisterUnscheduledAttentionUseCaseImpl(DoctorConfigConsultPort doctorConfigConsultPort,
                                                   ClinicalHistoryPort clinicalHistoryPort,
                                                   ManualPatientResolutionStrategy manualPatientResolutionStrategy,
                                                   AppointmentRepository appointmentRepository) {
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.clinicalHistoryPort = clinicalHistoryPort;
        this.manualPatientResolutionStrategy = manualPatientResolutionStrategy;
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    @Transactional
    public UUID register(UUID idDoctor,
                         PatientSchedulingContext patientContext,
                         SpecialtyCode specialty,
                         String medicalCheckup) {

        ResolvedPatient resolvedPatient = manualPatientResolutionStrategy.resolve(patientContext);

        Appointment appointment = Appointment.registerUnscheduledAttention(
                idDoctor,
                resolvedPatient.idPatient(),
                specialty
        );

        Appointment saved = appointmentRepository.save(appointment);

        if(medicalCheckup != null && !medicalCheckup.isBlank()) {
            String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);
            clinicalHistoryPort.registerClinicalHistory(
                    saved.getIdAppointment(),
                    saved.getIdPatient(),
                    doctorName,
                    medicalCheckup,
                    LocalDate.now());
        }

        return saved.getIdAppointment();
    }

    private void validateDoctorSpecialty(UUID idDoctor, SpecialtyCode specialty) {
        if (!doctorConfigConsultPort.getSpecialtiesByDoctor(idDoctor).contains(specialty)) {
            throw new DoctorSpecialtyMismatchException("El doctor no tiene habilitada la especialidad: " + specialty);
        }
    }
}
