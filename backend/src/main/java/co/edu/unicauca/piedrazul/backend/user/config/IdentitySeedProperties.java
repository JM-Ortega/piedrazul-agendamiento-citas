package co.edu.unicauca.piedrazul.backend.user.config;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.seed.identity")
public class IdentitySeedProperties {

    private boolean enabled;
    private SeedUser admin = new SeedUser();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SeedUser getAdmin() {
        return admin;
    }

    public void setAdmin(SeedUser admin) {
        this.admin = admin;
    }

    public static class SeedUser {
        private String username;
        private IdentificationType identificationType;
        private String firstName;
        private String lastName;
        private String email;
        private String phone;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public IdentificationType getIdentificationType() {
            return identificationType;
        }

        public void setIdentificationType(IdentificationType identificationType) {
            this.identificationType = identificationType;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
