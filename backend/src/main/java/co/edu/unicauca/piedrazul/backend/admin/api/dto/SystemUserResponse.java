package co.edu.unicauca.piedrazul.backend.admin.api.dto;

import java.util.List;
import java.util.UUID;

public record SystemUserResponse(
        UUID id,
        String firstName,
        String lastName,
        String documentId,
        List<String> roles,
        DoctorUserDataResponse doctorData
) {}