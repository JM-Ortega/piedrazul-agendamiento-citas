package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

public interface AppointmentConfigRepository {
    boolean isAutonomousSchedulingEnabled();
    void setAutonomousSchedulingEnabled(boolean enabled);
}
