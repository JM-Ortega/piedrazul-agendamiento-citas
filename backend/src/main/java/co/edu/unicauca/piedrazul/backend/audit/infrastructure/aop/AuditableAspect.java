package co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
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
    private final SecurityContextExtractor securityExtractor;
    private final ExpressionParser parser = new SpelExpressionParser();

    public AuditableAspect(AuditEventRepository repository, SecurityContextExtractor securityExtractor) {
        this.repository = repository;
        this.securityExtractor = securityExtractor;
    }

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        String actorId = securityExtractor.currentActorId();
        String actorRole = securityExtractor.currentActorRoles();

        String targetId = resolveTargetId(pjp, auditable);

        try {
            Object result = pjp.proceed();
            save(actorId, actorRole, auditable, targetId, AuditOutcome.EXITOSO);
            return result;
        } catch (org.springframework.security.access.AccessDeniedException ex) {
            save(actorId, actorRole, auditable, targetId, AuditOutcome.DENEGADO);
            throw ex;
        } catch (Exception ex) {
            save(actorId, actorRole, auditable, targetId, AuditOutcome.FALLIDO);
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
