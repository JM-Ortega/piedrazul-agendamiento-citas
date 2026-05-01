@ApplicationModule(allowedDependencies = {
        "shared::auth",
        "user",
        "user::internal-dto",
        "doctors",
        "doctors::internal-dto"
})
package co.edu.unicauca.piedrazul.backend.admin;

import org.springframework.modulith.ApplicationModule;