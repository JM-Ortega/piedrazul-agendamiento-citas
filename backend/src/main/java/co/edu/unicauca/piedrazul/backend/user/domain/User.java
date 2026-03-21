package co.edu.unicauca.piedrazul.backend.user.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
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
            throw new IllegalArgumentException("username cannot be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role cannot be null");
        }

        this.username = username;
        this.role = role;
        this.accountStatus = AccountStatus.ACTIVE;
    }

    public void activate() {
        this.accountStatus = AccountStatus.ACTIVE;
    }

    public void deactivate() {
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