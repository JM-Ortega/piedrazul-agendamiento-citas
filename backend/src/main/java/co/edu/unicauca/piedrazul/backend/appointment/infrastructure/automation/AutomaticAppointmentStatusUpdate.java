package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.automation;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateExpiredAppointmentsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AutomaticAppointmentStatusUpdate {

    private final UpdateExpiredAppointmentsUseCase useCase;

    //"0 0 1 * * *"
    //*/30 * * * * *
    @Scheduled(cron = "0 0 1 * * *" )
    public void execute(){
        useCase.updateExpiredAppointments();
    }

}
