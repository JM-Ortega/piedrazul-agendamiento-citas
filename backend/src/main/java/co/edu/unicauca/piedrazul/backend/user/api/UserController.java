package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSchedulerRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/system-users")
    public List<SystemUserResponse> getSystemUsers() {
        return userService.getSystemUsers();
    }

    @PostMapping("/schedulers")
    public void createScheduler(@Valid @RequestBody CreateSchedulerRequest request) {
        userService.createScheduler(request);
    }
}
