@ApplicationModule(allowedDependencies = {"patients::api", "doctors::api", "doctors", "patients", "shared",
                                        "shared :: events", "doctors :: output-dtos"})
package co.edu.unicauca.piedrazul.backend.appointment;

import org.springframework.modulith.ApplicationModule;