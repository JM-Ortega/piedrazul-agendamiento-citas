package co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections;

import java.util.UUID;

/*
    ¿Qué es una proyección?
    Una proyección es una forma de decirle a Spring:
    "No necesito toda la entidad Person, solo quiero estos campos."

    En lugar de hacer:
    Person person = personRepository.findById(id);

    que carga todas las columnas:
    id
    userId
    identificationType
    identification
    firstName
    lastName
    phone
    email

    puedes pedir únicamente:
    id
    fullName

    para ahorrar memoria y trabajo.
 */

public interface PersonNameProjection {
    UUID getId();
    String getFullName();
}