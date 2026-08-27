@ApplicationModule(allowedDependencies = {"shared", "user", "jackson :: sanitization", "jackson :: normalization", "jackson :: validation", "appointment", "user :: internal-dto", "shared :: auth", "user :: input-dto"})
package co.edu.unicauca.piedrazul.backend.doctors;

import org.springframework.modulith.ApplicationModule;