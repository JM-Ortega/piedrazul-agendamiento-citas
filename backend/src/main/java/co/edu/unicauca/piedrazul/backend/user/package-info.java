@ApplicationModule(allowedDependencies = {"shared::auth", "config::security", "doctors", "jackson :: normalization", "jackson :: sanitization", "patients", "jackson :: validation", "doctors :: internal-dto", "patients :: api", "shared", "shared :: enums", "shared :: audit-events"})
package co.edu.unicauca.piedrazul.backend.user;

import org.springframework.modulith.ApplicationModule;