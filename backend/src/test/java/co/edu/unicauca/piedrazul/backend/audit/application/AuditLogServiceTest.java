package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditLog;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModule;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    void registerShouldPersistAuditLogUsingSchemaAlignedFields() {
        AuditLogService auditLogService = new AuditLogService(auditLogRepository);
        UUID entityId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID performedBy = UUID.fromString("22222222-2222-2222-2222-222222222222");

        auditLogService.register(
                AuditAction.APPOINTMENT_CREATED,
                AuditModule.APPOINTMENT,
                entityId,
                performedBy
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();

        assertThat(ReflectionTestUtils.getField(saved, "id")).isInstanceOf(UUID.class);
        assertThat(ReflectionTestUtils.getField(saved, "action")).isEqualTo(AuditAction.APPOINTMENT_CREATED);
        assertThat(ReflectionTestUtils.getField(saved, "module")).isEqualTo(AuditModule.APPOINTMENT);
        assertThat(ReflectionTestUtils.getField(saved, "entityId")).isEqualTo(entityId);
        assertThat(ReflectionTestUtils.getField(saved, "performedBy")).isEqualTo(performedBy);
        assertThat(ReflectionTestUtils.getField(saved, "performedAt")).isInstanceOf(LocalDateTime.class);
    }
}