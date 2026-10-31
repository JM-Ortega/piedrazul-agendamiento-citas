package co.edu.unicauca.piedrazul.backend.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Entradas del bootstrap del primer administrador de negocio ({@code IDENTITY_SEED_*}).
 * Con {@code enabled=false} (por defecto) no se usa ninguna, tampoco la contraseña.
 */
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
        // Texto, no enum: un valor desconocido se informa como entrada inválida del bootstrap
        // en vez de impedir el arranque al enlazar propiedades.
        private String identificationType;
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

        public String getIdentificationType() {
            return identificationType;
        }

        public void setIdentificationType(String identificationType) {
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
