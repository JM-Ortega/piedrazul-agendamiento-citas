package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record DoctorAdminUserData(
        UUID doctorId,
        UUID userId,
        String firstName,
        String lastName,
        String identification,
        String specialty,
        int appointmentInterval,
        boolean status,
        String startTime,
        String endTime
) {
    public static DoctorAdminUserData fromEntity(Doctor doctor) {
        List<Schedule> schedules = Optional.ofNullable(doctor.getSchedules())
                .orElse(List.of())
                .stream()
                .sorted(Comparator.comparing(Schedule::getWorkday))
                .toList();

        LocalTime start = schedules.stream()
                .map(Schedule::getStartTime)
                .min(LocalTime::compareTo)
                .orElse(null);

        LocalTime end = schedules.stream()
                .map(Schedule::getEndTime)
                .max(LocalTime::compareTo)
                .orElse(null);

        return new DoctorAdminUserData(
                doctor.getIdDoctor(),
                doctor.getIdUser(),
                doctor.getFirstName(),
                doctor.getLastName(),
                doctor.getIdentification(),
                doctor.getSpecialty().toString(),
                doctor.getAppointmentInterval(),
                doctor.isStatus(),
                start != null ? start.toString() : "",
                end != null ? end.toString() : ""
        );
    }
}