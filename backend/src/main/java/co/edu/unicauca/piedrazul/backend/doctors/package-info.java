@ApplicationModule(allowedDependencies = {"shared", "user", "jackson :: sanitization", "jackson :: normalization", "jackson :: validation", "appointment", "user :: internal-dto", "shared :: auth", "user :: input-dto", "shared :: enums", "shared :: pagination", "shared :: audit-events"})
package co.edu.unicauca.piedrazul.backend.doctors;

import org.springframework.modulith.ApplicationModule;