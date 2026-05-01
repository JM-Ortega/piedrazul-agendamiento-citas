package co.edu.unicauca.piedrazul.backend.admin.application;

import co.edu.unicauca.piedrazul.backend.admin.api.dto.CreateSchedulerRequest;
import co.edu.unicauca.piedrazul.backend.admin.api.dto.DoctorUserDataResponse;
import co.edu.unicauca.piedrazul.backend.admin.api.dto.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.admin.exception.AdminUserAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorAdminUserData;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdminSystemUserService {

    private final DoctorExternalService doctorService;
    private final UserModuleApi userModuleApi;

    public AdminSystemUserService(
            DoctorExternalService doctorService,
            UserModuleApi userModuleApi
    ) {
        this.doctorService = doctorService;
        this.userModuleApi = userModuleApi;
    }

    public void createScheduler(CreateSchedulerRequest request) {
        if (userModuleApi.findUserIdByUsername(request.documentId()).isPresent()) {
            throw new AdminUserAlreadyExistsException(request.documentId());
        }

        userModuleApi.getOrCreateSchedulerUser(
                request.documentId(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.password()
        );
    }

    public List<SystemUserResponse> getSystemUsers() {

        List<DoctorAdminUserData> doctors = doctorService.getAdminUserData();
        List<UserSummary> schedulers = userModuleApi.findSchedulers();

        Set<UUID> schedulerIds = schedulers.stream()
                .map(UserSummary::id)
                .collect(Collectors.toSet());

        List<SystemUserResponse> result = new ArrayList<>();

        for (DoctorAdminUserData doctor : doctors) {

            boolean isScheduler = schedulerIds.contains(doctor.userId());

            List<String> roles = new ArrayList<>();
            roles.add("doctor");
            if (isScheduler) roles.add("scheduler");

            result.add(new SystemUserResponse(
                    doctor.doctorId(),
                    doctor.firstName(),
                    doctor.lastName(),
                    doctor.identification(),
                    roles,
                    new DoctorUserDataResponse(
                            doctor.specialty(),
                            doctor.startTime(),
                            doctor.endTime(),
                            doctor.appointmentInterval()
                    )
            ));
        }

        for (UserSummary scheduler : schedulers) {

            boolean isDoctor = doctors.stream()
                    .anyMatch(d -> d.userId().equals(scheduler.id()));

            if (isDoctor) continue;

            result.add(new SystemUserResponse(
                    scheduler.id(),
                    scheduler.firstName(),
                    scheduler.lastName(),
                    scheduler.username(),
                    List.of("scheduler"),
                    null
            ));
        }

        return result;
    }
}