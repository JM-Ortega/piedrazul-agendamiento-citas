package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class AppointmentService {
    private final BusySlotService busySlotService;

    public AppointmentService(BusySlotService busySlotService) {
        this.busySlotService = busySlotService;
    }

    // El agendador crea la cita manualmente
    public Appointment scheduleManual(String doctorName,
                                      UUID idPatient,
                                      PatientInfo patientInfo,
                                      UUID idDoctor,
                                      String patientName,
                                      Specialty specialty,
                                      LocalDate date,
                                      AppointmentTime startTime,
                                      int intervalMinutes,
                                      List<Appointment> existingAppointments) {
        return schedule(
            doctorName,
            idDoctor,
            idPatient,
            patientName,
            patientInfo,
            specialty,
            date,
            startTime,
            intervalMinutes,
            existingAppointments,
            SchedulingOrigin.MANUAL
        );
    }

    // El paciente web agenda de forma autónoma
    public Appointment scheduleAutonomous(String doctorName,
                                          UUID idPatient,
                                          PatientInfo patientInfo,
                                          UUID idDoctor,
                                          String patientName,
                                          Specialty specialty,
                                          LocalDate date,
                                          AppointmentTime startTime,
                                          int intervalMinutes,
                                          List<Appointment> existingAppointments) {
        return schedule(
                doctorName,
                idDoctor,
                idPatient,
                patientName,
                patientInfo,
                specialty,
                date,
                startTime,
                intervalMinutes,
                existingAppointments,
                SchedulingOrigin.AUTONOMO
        );
    }

    private Appointment schedule(String doctorName,
                                 UUID idDoctor,
                                 UUID idPatient,
                                 String patientName,
                                 PatientInfo patientInfo,
                                 Specialty specialty,
                                 LocalDate date,
                                 AppointmentTime startTime,
                                 int intervalMinutes,
                                 List<Appointment> existingAppointments,
                                 SchedulingOrigin origin) {

        // El dominio valida que el slot esté libre antes de crear la cita
        if (busySlotService.isBusy(existingAppointments, startTime, intervalMinutes)) {
            throw new SlotNotAvailableException(
                    "El slot " + startTime + " ya está ocupado para este médico"
            );
        }

        if (origin == SchedulingOrigin.MANUAL) {
            return Appointment.scheduleManual(
                    doctorName,
                    idDoctor,
                    idPatient,
                    patientName,
                    patientInfo,
                    specialty,
                    date,
                    startTime
            );
        }

        return Appointment.scheduleAutonomous(
                doctorName,
                idDoctor,
                idPatient,
                patientName,
                patientInfo,
                specialty,
                date,
                startTime
        );
    }

}
