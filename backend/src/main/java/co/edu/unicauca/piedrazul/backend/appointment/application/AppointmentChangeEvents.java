package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentCancelledByStaffEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentRescheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Set;
import java.util.UUID;

/**
 * Arma y publica los eventos de cancelación por el personal y de reagendamiento. Son avisos
 * informativos: si falta el paciente o un nombre de médico no se publica nada (sin inventar datos)
 * y la operación de la cita sigue su curso. Solo usa consultas que no lanzan cuando falta el dato.
 */
public class AppointmentChangeEvents {

    private static final Logger log = LoggerFactory.getLogger(AppointmentChangeEvents.class);

    private final ApplicationEventPublisher eventPublisher;
    private final PatientConsultPort patientConsultPort;
    private final DoctorConfigConsultPort doctorConfigConsultPort;

    public AppointmentChangeEvents(
            ApplicationEventPublisher eventPublisher,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort) {
        this.eventPublisher = eventPublisher;
        this.patientConsultPort = patientConsultPort;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
    }

    public void staffCancelled(Appointment cancelled) {
        PatientInfo patient = patientOf(cancelled.getIdPatient());
        String doctorName = doctorConfigConsultPort.getDoctorName(cancelled.getIdDoctor());

        if (patient == null || doctorName == null) {
            skipped("cancelación", cancelled.getIdAppointment());
            return;
        }

        eventPublisher.publishEvent(new AppointmentCancelledByStaffEvent(
                cancelled.getIdAppointment(),
                cancelled.getIdPatient(),
                fullName(patient),
                patient.getPhone(),
                patient.getEmail(),
                doctorName,
                cancelled.getDate(),
                cancelled.getStartTime().getTime(),
                cancelled.getSpecialty().name()));
    }

    public void rescheduled(Appointment previous, Appointment replacement) {
        PatientInfo patient = patientOf(previous.getIdPatient());
        String previousDoctorName = doctorConfigConsultPort.getDoctorName(previous.getIdDoctor());
        String doctorName = doctorConfigConsultPort.getDoctorName(replacement.getIdDoctor());

        if (patient == null || previousDoctorName == null || doctorName == null) {
            skipped("reagendamiento", previous.getIdAppointment());
            return;
        }

        eventPublisher.publishEvent(new AppointmentRescheduledEvent(
                previous.getIdAppointment(),
                replacement.getIdAppointment(),
                previous.getIdPatient(),
                fullName(patient),
                patient.getPhone(),
                patient.getEmail(),
                previousDoctorName,
                previous.getDate(),
                previous.getStartTime().getTime(),
                previous.getSpecialty().name(),
                doctorName,
                replacement.getDate(),
                replacement.getStartTime().getTime(),
                replacement.getSpecialty().name()));
    }

    private PatientInfo patientOf(UUID patientId) {
        return patientConsultPort.findByIds(Set.of(patientId)).get(patientId);
    }

    private static String fullName(PatientInfo patient) {
        return patient.getFirstName() + " " + patient.getLastName();
    }

    private static void skipped(String operation, UUID appointmentId) {
        log.warn("Aviso de {} omitido: faltan datos del paciente o del médico. appointmentId={}",
                operation, appointmentId);
    }
}
