package co.edu.unicauca.piedrazul.backend.user.domain;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyActiveException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyInactiveException;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id_user", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus;

    protected User() {
    }

    public User(String username, Role role) {

        if (username == null || username.isBlank()) {
            throw new InvalidUserDataException("Username cannot be blank");
        }

        if (role == null) {
            throw new InvalidUserDataException("Role cannot be null");
        }

        this.username = username;
        this.role = role;
        this.accountStatus = AccountStatus.ACTIVE;
    }

    public void activate() {
        if (this.accountStatus == AccountStatus.ACTIVE) {
            throw new UserAlreadyActiveException(this.id);
        }
        this.accountStatus = AccountStatus.ACTIVE;
    }

    public void deactivate() {
        if (this.accountStatus == AccountStatus.INACTIVE) {
            throw new UserAlreadyInactiveException(this.id);
        }
        this.accountStatus = AccountStatus.INACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public Role getRole() {
        return role;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }
}