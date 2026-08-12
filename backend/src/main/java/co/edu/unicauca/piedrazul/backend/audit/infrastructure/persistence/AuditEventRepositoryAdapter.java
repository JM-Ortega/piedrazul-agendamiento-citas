package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class AuditEventRepositoryAdapter implements AuditEventRepository {

    private final AuditEventJpaRepository jpaRepository;

    public AuditEventRepositoryAdapter(AuditEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(AuditEvent event) {
        jpaRepository.save(toEntity(event));
    }

    @Override
    public AuditEventPage findByCriteria(String actorUsername, AuditAction action,
                                         String targetEntityType, String targetEntityId, Instant from, Instant to,
                                         int page, int size) {

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));

        var result = jpaRepository.search(
                actorUsername, action, targetEntityType, targetEntityId, from, to, pageable);

        // Aquí es donde el adapter traduce el tipo de framework (Page de Spring Data)
        // al tipo de dominio (AuditEventPage) — la frontera se mantiene en un solo lugar.
        return new AuditEventPage(
                result.getContent().stream().map(this::toDomain).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements()
        );
    }

    private AuditEvent toDomain(AuditEventJpaEntity e) {
        return AuditEvent.reconstruct(e.getId(), e.getTimestamp())
                .actor(e.getActorUsername(), e.getActorRole())
                .action(e.getAction())
                .target(e.getTargetEntityType(), e.getTargetEntityId())
                .outcome(e.getOutcome())
                .correlationId(e.getCorrelationId())
                .states(e.getBeforeState(), e.getAfterState())
                .build();
    }

    private AuditEventJpaEntity toEntity(AuditEvent event) {
        return new AuditEventJpaEntity(
                event.getId(), event.getTimestamp(), event.getActorUsername(), event.getActorRole(),
                event.getAction(), event.getTargetEntityType(), event.getTargetEntityId(),
                event.getOutcome(), event.getCorrelationId(),
                event.getBeforeState(), event.getAfterState()
        );
    }
}
