package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import org.jmolecules.ddd.annotation.Service;
import org.springframework.stereotype.Component;

@Service
@Component
public class AuditQueryService {

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public AuditEventPage search(AuditEventCriteria criteria) {
        return repository.findByCriteria(
                criteria.actorUsername(),
                criteria.action(),
                criteria.targetEntityType(),
                criteria.targetEntityId(),
                criteria.from(),
                criteria.to(),
                criteria.page(),
                criteria.size()
        );
    }
}