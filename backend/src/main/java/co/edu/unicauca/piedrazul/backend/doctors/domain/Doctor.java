package co.edu.unicauca.piedrazul.backend.doctors.domain;

import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorValidationException;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@RequiredArgsConstructor
@Getter
@Entity
@Table(name = "doctor", schema = "piedrazul")
public class Doctor {

    @Id
    @Column(name = "person_id", updatable = false, nullable = false)
    private UUID personId;

    @Column(name = "labor_start", nullable = false)
    private LocalDate laborStart;

    @Column(name = "labor_end", nullable = false)
    private LocalDate laborEnd;

    @Column(name = "booking_window_weeks")
    private int bookingWindowWeeks;

    @Column(name = "status", nullable = false)
    private boolean status;

    @Column(name = "appointment_interval")
    private int appointmentInterval;

    @OneToMany(mappedBy = "doctor", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Schedule> schedules = new HashSet<>();

    @ManyToMany
    @JoinTable(
            name = "doctor_specialty",
            schema = "piedrazul",
            joinColumns = @JoinColumn(name = "doctor_id"),
            inverseJoinColumns = @JoinColumn(name = "specialty_code")
    )
    private Set<Specialty> specialties = new HashSet<>();

    // Al momento de registrar/crearle una cuenta al doctor se le deben llenar todos estos campos,
    // el registro de doctores deberia hacerlo solo el administrador
    public Doctor(UUID personId, LocalDate laborStart, LocalDate laborEnd,
                  int bookingWindowWeeks, Boolean status, Integer appointmentInterval) {
        this.personId = personId;
        this.laborStart = laborStart;
        this.laborEnd = laborEnd;
        this.bookingWindowWeeks = bookingWindowWeeks;
        this.status = status;
        this.appointmentInterval = appointmentInterval;
    }

    public void updateSchedule(
            Workday workday,
            LocalTime startTime,
            LocalTime endTime
    ) {
        Schedule schedule = schedules.stream()
                .filter(s -> s.getWorkday() == workday)
                .findFirst()
                .orElse(null);

        if (schedule == null) {
            if (!endTime.isAfter(startTime)) {
                throw new DoctorValidationException(
                        "La hora final debe ser posterior a la inicial");
            }

            schedules.add(new Schedule(
                    this,
                    startTime,
                    endTime,
                    workday
            ));
        } else {
            schedule.updateHours(startTime, endTime);
        }
    }

    public void removeSchedule(Workday workday) {

        schedules.removeIf(schedule ->
                schedule.getWorkday() == workday);

    }

    public void updateLaborPeriod(LocalDate laborStart, LocalDate laborEnd) {
        if (laborStart == null) {
            throw new DoctorValidationException(
                    "El médico no tiene una fecha de inicio laboral."
            );
        }

        if (laborEnd == null) {
            throw new DoctorValidationException(
                    "El médico no tiene una fecha de finalización laboral."
            );
        }

        if (laborEnd.isBefore(laborStart)) {
            throw new DateConflictException(
                    "La fecha de finalización no puede ser anterior a la fecha de inicio."
            );
        }

        this.laborStart = laborStart;
        this.laborEnd = laborEnd;
    }

    public void updateBookingWindow(int weeks) {
        if (weeks <= 0) {
            throw new DoctorValidationException(
                    "La ventana de agendamiento debe ser mayor que cero");
        }

        this.bookingWindowWeeks = weeks;
    }

    public void updateAppointmentInterval(int minutes) {

        if (minutes <= 0) {
            throw new DoctorValidationException(
                    "El intervalo debe ser mayor que cero");
        }

        // Evita intervalos imposibles para los horarios existentes
        if (!schedules.isEmpty()) {

            boolean valid = schedules.stream()
                    .anyMatch(schedule -> Duration.between(
                            schedule.getStartTime(),
                            schedule.getEndTime()
                    ).toMinutes() >= minutes);

            if (!valid) {
                throw new DoctorValidationException(
                        "El intervalo es mayor que la duración de algunos o todos los horarios");
            }
        }

        this.appointmentInterval = minutes;
    }

    public void addSpecialty(Specialty specialty) {
        specialties.add(specialty);
    }

    public void replaceSpecialties(Set<Specialty> specialties) {
        this.specialties.clear();
        this.specialties.addAll(specialties);
    }

    public boolean hasSpecialtie(SpecialtyCode code) {
        return specialties.stream().anyMatch(s -> s.getCode().equals(code));
    }

    public void deactivate() {
        this.status = false;
    }

    public void activate() {

        validateCanBeActivated();

        this.status = true;
    }

    public void activateIfPossible() {
        this.status = canBeActivated();
    }

    public boolean canBeActivated() {
        try {
            validateCanBeActivated();
            return true;
        } catch (DoctorValidationException | DateConflictException ex) {
            return false;
        }
    }

    private void validateCanBeActivated() {
        if (laborStart == null) {
            throw new DoctorValidationException(
                    "El médico no tiene una fecha de inicio laboral."
            );
        }

        if (laborEnd == null) {
            throw new DoctorValidationException(
                    "El médico no tiene una fecha de finalización laboral."
            );
        }

        if (laborEnd.isBefore(laborStart)) {
            throw new DateConflictException(
                    "La fecha de finalización no puede ser anterior a la fecha de inicio."
            );
        }

        if (laborEnd.isBefore(LocalDate.now())) {
            throw new DateConflictException(
                    "La fecha de finalización no puede ser anterior a el dia de hoy"
            );
        }

        if (bookingWindowWeeks <= 0) {
            throw new DoctorValidationException(
                    "El médico debe tener una ventana de agendamiento válida."
            );
        }

        if (appointmentInterval <= 0) {
            throw new DoctorValidationException(
                    "El médico debe tener un intervalo de atención válido."
            );
        }

        if (schedules.isEmpty()) {
            throw new DoctorValidationException(
                    "El médico debe tener al menos un horario asignado."
            );
        }

        if (specialties.isEmpty()) {
            throw new DoctorValidationException(
                    "El médico debe tener al menos una especialidad asignada."
            );
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Doctor other)) return false;
        return personId != null && personId.equals(other.personId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(personId);
    }
}