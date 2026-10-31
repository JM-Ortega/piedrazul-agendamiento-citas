package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventQuery;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import org.jmolecules.ddd.annotation.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
@Component
public class AuditQueryService {

    private final AuditEventRepository repository;
    private final ZoneId zone;

    public AuditQueryService(AuditEventRepository repository,
                             @Value("${app.timezone:America/Bogota}") String timezone) {
        this.repository = repository;
        this.zone = ZoneId.of(timezone);
    }

    public AuditEventPage search(AuditEventCriteria criteria) {
        return repository.findByCriteria(new AuditEventQuery(
                startOf(criteria.from()),
                criteria.to() == null ? null : startOf(criteria.to().plusDays(1)),
                criteria.action(),
                criteria.moduleCode(),
                criteria.outcome(),
                criteria.search(),
                criteria.targetEntityId(),
                criteria.page(),
                criteria.size()
        ));
    }

    /** Primer instante del día en la zona horaria de la aplicación. */
    private Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(zone).toInstant();
    }
}
