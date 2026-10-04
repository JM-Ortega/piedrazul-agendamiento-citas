@ApplicationModule(allowedDependencies = {"shared :: audit-events", "shared :: enums", "appointment :: output-dto", "shared :: pagination", "appointment :: events", "user :: events", "doctors :: events", "patients :: events", "medicalCheckup :: events", "shared"})
package co.edu.unicauca.piedrazul.backend.audit;

import org.springframework.modulith.ApplicationModule;