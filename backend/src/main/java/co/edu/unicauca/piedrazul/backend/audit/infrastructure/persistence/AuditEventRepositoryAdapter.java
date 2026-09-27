package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventQuery;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AuditEventRepositoryAdapter implements AuditEventRepository {

    /**
     * El nombre y el usuario de quien actuó se leen de {@code person} al consultar, cruzando por el
     * id de la cuenta. La bitácora es append-only y no guarda datos personales: así siguen vigentes
     * aunque la persona cambie de nombre o de documento. Se cruza en SQL, y no en memoria, para
     * poder buscar por ellos sin traer todos los registros.
     */
    private static final String JOIN_PERSON =
            "LEFT JOIN piedrazul.person p ON CAST(p.user_id AS text) = a.actor_id ";

    /**
     * El módulo de cada acción viene del catálogo, no de la fila de auditoría. Es {@code JOIN}, no
     * {@code LEFT JOIN}, porque {@code audit_event.action_code} tiene clave foránea a
     * {@code audit_action}: toda fila referencia una acción del catálogo (ver
     * {@code AuditActionCatalogIT}).
     */
    private static final String JOIN_ACTION_MODULE =
            "JOIN piedrazul.audit_action aa ON aa.code = a.action_code "
                    + "JOIN piedrazul.audit_module am ON am.code = aa.audit_module_code ";

    /**
     * Cada palabra de la búsqueda debe aparecer en el nombre completo o en el documento, en cualquier
     * orden y sin que estén seguidas: "jose garcia" encuentra a "José Ignacio García". El marcador
     * {@code :search} se numera por palabra.
     */
    private static final String SEARCH_PREDICATE = """
            (extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name))
                 LIKE extensions.immutable_unaccent(lower('%' || :search || '%')) ESCAPE '\\'
             OR lower(p.identification) LIKE lower('%' || :search || '%') ESCAPE '\\')
            """;

    private final AuditEventJpaRepository jpaRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
    @Transactional(readOnly = true)
    public AuditEventPage findByCriteria(AuditEventQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(query, params);

        Query select = entityManager.createNativeQuery(
                "SELECT a.id, a.occurred_at, a.actor_id, a.actor_role, a.action_code, am.code, am.name, "
                        + "a.outcome, a.target_entity_type, a.target_entity_id, a.correlation_id, "
                        + "p.first_name, p.last_name, p.identification "
                        + "FROM piedrazul.audit_event a " + JOIN_ACTION_MODULE + JOIN_PERSON + where
                        + " ORDER BY a.occurred_at DESC, a.id DESC");
        params.forEach(select::setParameter);
        select.setFirstResult(query.page() * query.size());
        select.setMaxResults(query.size());

        List<AuditEventView> content = new ArrayList<>();
        for (Object row : select.getResultList()) {
            content.add(toView((Object[]) row));
        }

        // El cruce con person solo hace falta para contar cuando se busca por nombre o documento;
        // el del catálogo de acciones siempre, porque el filtro por módulo lo necesita.
        Query count = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM piedrazul.audit_event a " + JOIN_ACTION_MODULE
                        + (query.search() != null ? JOIN_PERSON : "") + where);
        params.forEach(count::setParameter);
        long total = ((Number) count.getSingleResult()).longValue();

        return new AuditEventPage(content, query.page(), query.size(), total);
    }

    private String where(AuditEventQuery query, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();

        if (query.from() != null) {
            conditions.add("a.occurred_at >= :from");
            params.put("from", query.from());
        }
        if (query.toExclusive() != null) {
            conditions.add("a.occurred_at < :toExclusive");
            params.put("toExclusive", query.toExclusive());
        }
        if (query.action() != null) {
            conditions.add("a.action_code = :action");
            params.put("action", query.action().name());
        }
        if (query.moduleCode() != null) {
            conditions.add("am.code = :moduleCode");
            params.put("moduleCode", query.moduleCode());
        }
        if (query.outcome() != null) {
            conditions.add("a.outcome = :outcome");
            params.put("outcome", query.outcome().name());
        }
        if (query.actorId() != null) {
            conditions.add("a.actor_id = :actorId");
            params.put("actorId", query.actorId());
        }
        if (query.targetEntityType() != null) {
            conditions.add("a.target_entity_type = :targetEntityType");
            params.put("targetEntityType", query.targetEntityType());
        }
        if (query.targetEntityId() != null) {
            conditions.add("a.target_entity_id = :targetEntityId");
            params.put("targetEntityId", query.targetEntityId());
        }
        if (query.search() != null) {
            String[] words = query.search().split(" ");
            for (int i = 0; i < words.length; i++) {
                conditions.add(SEARCH_PREDICATE.replace(":search", ":search" + i));
                params.put("search" + i, escapeLike(words[i]));
            }
        }

        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static String escapeLike(String term) {
        return term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static AuditEventView toView(Object[] row) {
        String firstName = (String) row[11];
        String lastName = (String) row[12];
        String actorName = firstName == null ? null : (firstName + " " + lastName).trim();

        return new AuditEventView(
                (UUID) row[0],
                toInstant(row[1]),
                (String) row[2],
                (String) row[3],
                actorName,
                (String) row[13],
                (String) row[4],
                (String) row[7],
                (String) row[8],
                (String) row[9],
                (String) row[10],
                (String) row[5],
                (String) row[6]
        );
    }

    // Según el controlador JDBC y la versión de Hibernate, timestamptz llega con distintos tipos.
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Tipo de fecha inesperado en audit_event: " + value.getClass());
    }
}
