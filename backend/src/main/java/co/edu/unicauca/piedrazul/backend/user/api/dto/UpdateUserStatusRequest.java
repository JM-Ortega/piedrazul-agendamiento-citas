package co.edu.unicauca.piedrazul.backend.user.api.dto;

import co.edu.unicauca.piedrazul.backend.user.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public class UpdateUserStatusRequest {

    @NotNull(message = "accountStatus is required")
    private AccountStatus accountStatus;

    public UpdateUserStatusRequest() {
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(AccountStatus accountStatus) {
        this.accountStatus = accountStatus;
    }
}