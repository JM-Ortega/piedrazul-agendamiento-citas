package co.edu.unicauca.piedrazul.backend.doctors.exception;

public class DoctorHasScheduledAppointments extends RuntimeException {
  public DoctorHasScheduledAppointments(String message) {
    super(message);
  }
}
