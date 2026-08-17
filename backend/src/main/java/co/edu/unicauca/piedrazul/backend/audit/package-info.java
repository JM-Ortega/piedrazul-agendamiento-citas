@ApplicationModule(allowedDependencies = {"shared :: audit-events", "shared :: enums", "appointment :: output-dto", "shared :: pagination", "appointment :: events", "user :: events"})
package co.edu.unicauca.piedrazul.backend.audit;

import org.springframework.modulith.ApplicationModule;