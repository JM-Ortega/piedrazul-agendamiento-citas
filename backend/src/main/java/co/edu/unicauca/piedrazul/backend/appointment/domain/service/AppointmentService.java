package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.SlotNotAvailableException;


import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class AppointmentService {
    private final BusySlotService busySlotService;
    private final  SlotTimeService slotTimeService;

    public AppointmentService(BusySlotService busySlotService, SlotTimeService slotTimeService) {
        this.busySlotService = busySlotService;
        this.slotTimeService = slotTimeService;
    }

    // El agendador crea la cita manualmente
    public Appointment scheduleManual(String doctorName,
                                      PatientInfo patientInfo,
                                      UUID idDoctor,
                                      Specialty specialty,
                                      LocalDate date,
                                      AppointmentTime startTime,
                                      int intervalMinutes,
                                      List<Appointment> existingAppointments) {

        // El dominio valida que el slot esté libre antes de crear la cita
        if (busySlotService.isBusy(
                existingAppointments, startTime, intervalMinutes)) {
            throw new SlotNotAvailableException(
                    "El slot " + startTime + " ya está ocupado para este médico"
            );
        }

        return Appointment.scheduleManual(
                doctorName, idDoctor, patientInfo, specialty, date, startTime
        );
    }

    // El paciente web agenda de forma autónoma
    public Appointment scheduleAutonomous(String doctorName,
                                          UUID idPatient,
                                          PatientInfo patientInfo,
                                          UUID idDoctor,
                                          Specialty specialty,
                                          LocalDate date,
                                          AppointmentTime startTime,
                                          int intervalMinutes,
                                          List<Appointment> existingAppointments) {

        // El dominio valida que el slot esté libre antes de crear la cita
        if (busySlotService.isBusy(existingAppointments, startTime, intervalMinutes)) {
            throw new SlotNotAvailableException(
                    "El slot " + startTime + " ya está ocupado para este médico"
            );
        }

        return Appointment.scheduleAutonomous(
                doctorName, idDoctor, idPatient, patientInfo, specialty, date, startTime
        );
    }

    // Franjas disponibles para mostrarle al frontend
    // doctorSlots viene del módulo de médicos ya calculadas para ese día
    // existingAppointments son las citas que ya existen en BD para ese médico y fecha
    public List<AppointmentTime> getAvailableSlots(List<AppointmentTime> doctorSlots,
                                                   List<Appointment> existingAppointments,
                                                   int intervalMinutes) {
        return slotTimeService.calculateAvailable(
                doctorSlots, existingAppointments, intervalMinutes
        );
    }
}
