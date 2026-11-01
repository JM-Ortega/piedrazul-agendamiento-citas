@ApplicationModule(allowedDependencies = {
        "appointment::events",
        "appointment::eligibility",
        "shared"
})
package co.edu.unicauca.piedrazul.backend.notifications;

import org.springframework.modulith.ApplicationModule;