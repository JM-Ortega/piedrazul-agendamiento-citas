package co.edu.unicauca.piedrazul.backend.user.api.dto;

import co.edu.unicauca.piedrazul.backend.user.domain.AccountStatus;
import co.edu.unicauca.piedrazul.backend.shared.auth.Role;

import java.util.UUID;

public class UserResponse {

    private UUID id;
    private String username;
    private Role role;
    private AccountStatus accountStatus;

    public UserResponse() {
    }

    public UserResponse(UUID id, String username, Role role, AccountStatus accountStatus) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.accountStatus = accountStatus;
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

    public void setId(UUID id) {
        this.id = id;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setAccountStatus(AccountStatus accountStatus) {
        this.accountStatus = accountStatus;
    }
}