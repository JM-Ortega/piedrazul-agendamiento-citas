package co.edu.unicauca.piedrazul.backend.support;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Fallos sintéticos para probar los manejadores por módulo. Cada manejador cubre los controladores
 * de su paquete, así que cada módulo declara, en su paquete de pruebas, una subclase con esta forma.
 * Sin {@code @RestController}: no son endpoints de la aplicación para EndpointAuthorizationTest.
 */
public abstract class DiagnosticsProbeEndpoints {

    public static final String MARKER = "SYN0000001";

    public record DatedRequest(LocalDate date) {
    }

    /** Con el marcador en el mensaje, en cada causa, en una suprimida y en el detalle de PostgreSQL. */
    private static RuntimeException failureWithSensitiveText() {
        ServerErrorMessage serverError = new ServerErrorMessage(
                "SERROR\0VERROR\0C23505\0Mduplicate key value violates unique constraint\0"
                        + "DKey (identification)=(" + MARKER + ") already exists.\0\0");
        PSQLException database = new PSQLException(serverError, true);
        RuntimeException failure = new DataIntegrityViolationException(
                "could not execute statement " + MARKER, new RuntimeException("causa " + MARKER, database));
        failure.addSuppressed(new RuntimeException("suprimida " + MARKER));
        return failure;
    }

    @GetMapping("/failure")
    public String failure() {
        throw failureWithSensitiveText();
    }

    @GetMapping("/illegal-state")
    public String illegalState() {
        throw new IllegalStateException("estado " + MARKER);
    }

    @GetMapping("/checked")
    public String checked() throws Exception {
        throw new Exception("verificada " + MARKER, failureWithSensitiveText());
    }

    @GetMapping("/denied")
    public String denied() {
        throw new AccessDeniedException("denegado " + MARKER);
    }

    @GetMapping("/resources/{id}")
    public String byId(@PathVariable UUID id) {
        return id.toString();
    }

    @PostMapping("/dated")
    public String dated(@RequestBody DatedRequest request) {
        return String.valueOf(request.date());
    }
}
