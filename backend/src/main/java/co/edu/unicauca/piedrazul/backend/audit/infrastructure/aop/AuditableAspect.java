package co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuditableAspect {

    private final AuditEventRepository repository;
    private final ExpressionParser parser = new SpelExpressionParser();

    public AuditableAspect(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "anonymous";
        String role = auth != null && !auth.getAuthorities().isEmpty()
                ? auth.getAuthorities().iterator().next().getAuthority() : "N/A";

        String targetId = resolveTargetId(pjp, auditable);

        try {
            Object result = pjp.proceed();
            repository.save(AuditEvent.builder()
                    .actor(username, role)
                    .action(auditable.action())
                    .target(auditable.targetEntityType(), targetId)
                    .outcome(AuditOutcome.EXITOSO)
                    .build());
            return result;
        } catch (org.springframework.security.access.AccessDeniedException ex) {
            repository.save(AuditEvent.builder()
                    .actor(username, role)
                    .action(auditable.action())
                    .target(auditable.targetEntityType(), targetId)
                    .outcome(AuditOutcome.DENEGADO)
                    .build());
            throw ex;
        } catch (Exception ex) {
            repository.save(AuditEvent.builder()
                    .actor(username, role)
                    .action(auditable.action())
                    .target(auditable.targetEntityType(), targetId)
                    .outcome(AuditOutcome.FALLIDO)
                    .build());
            throw ex;
        }
    }

    private String resolveTargetId(ProceedingJoinPoint pjp, Auditable auditable) {
        if (auditable.targetIdExpression().isBlank()) {
            return "N/A";
        }
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
            // para que si hay un error de extracción de id tumbe la operación de la clinica
            return "N/A";
        }
    }
}
