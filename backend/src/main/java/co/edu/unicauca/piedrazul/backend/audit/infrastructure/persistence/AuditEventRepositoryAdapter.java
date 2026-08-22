package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class AuditEventRepositoryAdapter implements AuditEventRepository {

    private final AuditEventJpaRepository jpaRepository;

    public AuditEventRepositoryAdapter(AuditEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(AuditEvent event) {
        jpaRepository.save(new AuditEventJpaEntity(
                event.getId(), event.getTimestamp(), event.getActorId(), event.getActorRole(),
                event.getAction(), event.getTargetEntityType(), event.getTargetEntityId(),
                event.getOutcome(), event.getCorrelationId(), event.getBeforeState(), event.getAfterState()));
    }

    @Override
    public AuditEventPage findByCriteria(String actorUsername, AuditAction action,
            String targetEntityType, String targetEntityId, Instant from, Instant to,
            int page, int size) {

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        Specification<AuditEventJpaEntity> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (actorUsername != null) {
                predicates.add(cb.equal(root.get("actorId"), actorUsername));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (targetEntityType != null) {
                predicates.add(cb.equal(root.get("targetEntityType"), targetEntityType));
            }
            if (targetEntityId != null) {
                predicates.add(cb.equal(root.get("targetEntityId"), targetEntityId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var result = jpaRepository.findAll(specification, pageable);

        // Aquí es donde el adapter traduce el tipo de framework (Page de Spring Data)
        // al tipo de dominio (AuditEventPage) asi la frontera se mantiene en un solo
        // lugar.
        return new AuditEventPage(
                result.getContent().stream().map(this::toDomain).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private AuditEvent toDomain(AuditEventJpaEntity e) {
        return AuditEvent.reconstruct(e.getId(), e.getOccurredAt())
                .actor(e.getActorId(), e.getActorRole())
                .action(e.getAction())
                .target(e.getTargetEntityType(), e.getTargetEntityId())
                .outcome(e.getOutcome())
                .correlationId(e.getCorrelationId())
                .states(e.getBeforeState(), e.getAfterState())
                .build();
    }
}
