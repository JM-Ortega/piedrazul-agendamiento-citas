package co.edu.unicauca.piedrazul.backend.user.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;

public final class PersonApiMapper {

    private PersonApiMapper() {
    }

    public static PersonSummary toSummary(Person source) {
        if (source == null) {
            throw new IllegalArgumentException("Person cannot be null");
        }

        return new PersonSummary(
                source.getId(),
                source.getUserId(),
                source.getIdentificationType(),
                source.getIdentification(),
                source.getFirstName(),
                source.getLastName(),
                source.getPhone(),
                source.getEmail()
        );
    }
}
