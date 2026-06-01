package co.edu.unicauca.piedrazul.backend.admin.api;

import co.edu.unicauca.piedrazul.backend.admin.api.dto.CreateSchedulerRequest;
import co.edu.unicauca.piedrazul.backend.admin.api.dto.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.admin.application.AdminSystemUserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSystemUserController {

    private final AdminSystemUserService service;

    public AdminSystemUserController(AdminSystemUserService service) {
        this.service = service;
    }

    @GetMapping("/system-users")
    public List<SystemUserResponse> getSystemUsers() {
        return service.getSystemUsers();
    }

    @PostMapping("/schedulers")
    public void createScheduler(@Valid @RequestBody CreateSchedulerRequest request) {
        service.createScheduler(request);
    }
}