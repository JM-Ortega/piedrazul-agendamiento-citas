package co.edu.unicauca.piedrazul.backend.admin.api.dto;

public record CreateSchedulerRequest(
        String documentId,
        String password,
        String firstName,
        String lastName,
        String email
) {}