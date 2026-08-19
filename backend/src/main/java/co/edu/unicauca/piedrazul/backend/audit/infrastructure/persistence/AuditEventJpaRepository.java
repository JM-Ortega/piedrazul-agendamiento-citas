package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventJpaEntity, UUID> {

    // Con Pageable, Spring Data genera AUTOMÁTICAMENTE la consulta de conteo
    // (COUNT) además de la de contenido, no hay que escribirla a mano.
    @Query("""
        SELECT e FROM AuditEventJpaEntity e
        WHERE (:actorId IS NULL OR e.actorId = :actorId)
          AND (:action IS NULL OR e.action = :action)
          AND (:targetEntityType IS NULL OR e.targetEntityType = :targetEntityType)
          AND (:targetEntityId IS NULL OR e.targetEntityId = :targetEntityId)
          AND (:from IS NULL OR e.occurredAt >= :from)
          AND (:to IS NULL OR e.occurredAt <= :to)
        """)
    Page<AuditEventJpaEntity> search(
            @Param("actorId") String actorId,
            @Param("action") AuditAction action,
            @Param("targetEntityType") String targetEntityType,
            @Param("targetEntityId") String targetEntityId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );
}