package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ListAppointmentsUseCaseImpl implements ListAppointmentsUseCase {
    private final AppointmentRepository appointmentRepository;

    public ListAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    // POLIMORFISMO
    @Override
    public List<Appointment> listBy(UUID idDoctor, UUID idPatient, LocalDate date) {
        // Caso 1: Los 3 filtros presentes
        if (idDoctor != null && idPatient != null && date != null) {
            return appointmentRepository.findByDoctorIdAndPatientIdAndDate(idDoctor, idPatient, date);
        }

        // Caso 2: Medico y paciente
        if (idDoctor != null && idPatient != null) {
            return appointmentRepository.findByDoctorIdAndPatientId(idDoctor, idPatient);
        }

        // Caso 3: Medico en fecha específica
        if (idDoctor != null && date != null) {
            return appointmentRepository.findByDoctorIdAndDate(idDoctor, date);
        }

        // Caso 4: Paciente en fecha específica
        if (idPatient != null && date != null) {
            return appointmentRepository.findByPatientIdAndDate(idPatient, date);
        }

        // Caso 5: Solo ID del médico (Todas sus citas)
        if (idDoctor != null) {
            return appointmentRepository.findByDoctorId(idDoctor);
        }

        // Caso 6: Solo ID del paciente (Todas sus citas)
        if (idPatient != null) {
            return appointmentRepository.findByPatientId(idPatient);
        }

        // Caso 7: Solo fecha (Todas las citas del día de todos los médicos)
        if (date != null) {
            return appointmentRepository.findByDate(date);
        }

        // Caso default: Si no llega nada se devuelve todas las citas
        return appointmentRepository.findAll();
    }
}
