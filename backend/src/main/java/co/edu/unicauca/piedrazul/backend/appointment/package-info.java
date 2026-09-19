@ApplicationModule(allowedDependencies = {"patients::api", "doctors::api", "doctors", "patients", "shared", "doctors::output-dtos", "jackson::sanitization", "user", "shared::audit-events", "shared::enums", "jackson::validation", "doctors :: internal-dto", "jackson :: normalization"})
package co.edu.unicauca.piedrazul.backend.appointment;

import org.springframework.modulith.ApplicationModule;