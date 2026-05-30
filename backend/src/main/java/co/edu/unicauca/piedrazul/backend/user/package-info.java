@ApplicationModule(allowedDependencies = {"shared::auth", "config::security", "doctors", "jackson :: normalization",
        "jackson :: sanitization", "patients", "jackson :: validation", "doctors :: internal-dto", "patients :: api"})
package co.edu.unicauca.piedrazul.backend.user;

import org.springframework.modulith.ApplicationModule;