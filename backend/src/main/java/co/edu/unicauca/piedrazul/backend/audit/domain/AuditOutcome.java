package co.edu.unicauca.piedrazul.backend.audit.domain;

public enum AuditOutcome {
    EXITOSO("Exitoso"),
    FALLIDO("Fallido"),
    DENEGADO("Denegado");

    private final String displayName;

    AuditOutcome(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
