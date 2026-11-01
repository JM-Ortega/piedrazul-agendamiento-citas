package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AppointmentEligibilityService implements AppointmentEligibility {

    private final AppointmentRepository appointmentRepository;

    public AppointmentEligibilityService(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    // Fuera de toda transacción: ni una instantánea ajena ni el contexto de persistencia del
    // llamador pueden devolver un estado anterior al último confirmado.
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean isScheduled(UUID appointmentId) {
        return appointmentRepository.existsByIdAndState(appointmentId, AppointmentState.AGENDADA);
    }
}
