@ApplicationModule(allowedDependencies = {
        "appointment::events",
        "shared",
        "config::validation"
})
package co.edu.unicauca.piedrazul.backend.notifications;

import org.springframework.modulith.ApplicationModule;