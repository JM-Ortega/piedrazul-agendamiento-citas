package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ListAppointmentsUseCaseImpl implements ListAppointmentsUseCase {
    private final AppointmentRepository appointmentRepository;

    public ListAppointmentsUseCaseImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    // POLIMORFISMO
    @Override
    public List<Appointment> listBy(UUID idDoctor, LocalDate date) {
        // Caso 1: Ambos parámetros presentes (Médico en fecha específica)
        if (idDoctor != null && date != null) {
            return appointmentRepository.findByDoctorIdAndDate(idDoctor, date);
        }

        // Caso 2: Solo ID del médico (Todas sus citas)
        if (idDoctor != null) {
            return appointmentRepository.findByDoctorId(idDoctor);
        }

        // Caso 3: Solo fecha (Todas las citas del día de todos los médicos)
        if (date != null) {
            return appointmentRepository.findByDate(date);
        }

        // Caso default: Si no llega nada se devuelve todas las citas
        return Collections.emptyList();
    }
}
