@ApplicationModule(allowedDependencies = {
        "patients::api",
        "doctors::api",
        "doctors",
        "patients",
        "shared",
        "shared::events",
        "doctors::output-dtos",
        "config::validation",
        "jackson::sanitization",
        "user",
        "shared::audit-events",
        "shared::enums",
        "jackson::validation",
        "clinicalHistory::"
})
package co.edu.unicauca.piedrazul.backend.appointment;

import org.springframework.modulith.ApplicationModule;