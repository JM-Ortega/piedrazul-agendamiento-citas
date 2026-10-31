package co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.audit.Auditable;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.stereotype.Component;

/**
 * Registra la ejecución de los métodos anotados con {@link Auditable}.
 *
 * <p>Corre por fuera de la seguridad por método ({@code @PreAuthorize}). Sin esa
 * prioridad el rechazo por permisos se lanza antes de que el aspecto intervenga y
 * nunca se registraría un {@code DENEGADO}.
 *
 * <p>El orden no puede ser {@code HIGHEST_PRECEDENCE} ni {@code FIRST}: ambos empatan
 * o preceden a {@code ExposeInvocationInterceptor}, el que deja disponible la invocación
 * actual, y el aspecto falla al leer sus argumentos. Debe caer entre ese y
 * {@code PRE_FILTER}. Se declara con {@link Ordered} porque {@code @Order} exige una
 * constante y el valor viene de un enum.
 */
@Aspect
@Component
public class AuditableAspect implements Ordered {

    private final AuditEventRepository repository;
    private final SecurityContextExtractor securityExtractor;
    private final ExpressionParser parser = new SpelExpressionParser();

    public AuditableAspect(AuditEventRepository repository, SecurityContextExtractor securityExtractor) {
        this.repository = repository;
        this.securityExtractor = securityExtractor;
    }

    @Override
    public int getOrder() {
        // Justo antes del primer interceptor de autorización, y después de ExposeInvocationInterceptor.
        return AuthorizationInterceptorsOrder.PRE_FILTER.getOrder() - 1;
    }

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        String actorId = securityExtractor.currentActorId();
        String actorRole = securityExtractor.currentActorRoles();

        String targetId = resolveTargetId(pjp, auditable);

        try {
            Object result = pjp.proceed();
            if (!auditable.onlyDenied()) {
                save(actorId, actorRole, auditable, targetId, AuditOutcome.EXITOSO);
            }
            return result;
        } catch (org.springframework.security.access.AccessDeniedException ex) {
            save(actorId, actorRole, auditable, targetId, AuditOutcome.DENEGADO);
            throw ex;
        } catch (Exception ex) {
            if (!auditable.onlyDenied()) {
                save(actorId, actorRole, auditable, targetId, AuditOutcome.FALLIDO);
            }
            throw ex;
        }
    }

    private void save(String actorId, String actorRole, Auditable auditable, String targetId, AuditOutcome outcome) {
        repository.save(AuditEvent.builder()
                .actor(actorId, actorRole)
                .action(auditable.action())
                .target(auditable.targetEntityType(), targetId)
                .outcome(outcome)
                .build());
    }

    private String resolveTargetId(ProceedingJoinPoint pjp, Auditable auditable) {
        if (auditable.targetIdExpression().isBlank()) return "N/A";
        try {
            String[] paramNames = ((MethodSignature) pjp.getSignature()).getParameterNames();
            Object[] args = pjp.getArgs();
            EvaluationContext context = new StandardEvaluationContext();
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
            Object value = parser.parseExpression(auditable.targetIdExpression()).getValue(context);
            return value != null ? value.toString() : "N/A";
        } catch (Exception ex) {
            return "N/A";
        }
    }
}
