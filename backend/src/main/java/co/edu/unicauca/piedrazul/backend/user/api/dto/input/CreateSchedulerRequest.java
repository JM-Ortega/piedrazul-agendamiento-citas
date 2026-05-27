package co.edu.unicauca.piedrazul.backend.user.api.dto.input;

public record CreateSchedulerRequest(
        String documentId,
        String password,
        String firstName,
        String lastName,
        String email
) {}