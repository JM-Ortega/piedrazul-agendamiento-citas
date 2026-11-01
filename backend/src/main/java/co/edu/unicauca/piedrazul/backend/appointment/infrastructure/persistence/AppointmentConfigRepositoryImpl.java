package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentConfigEntity;
import jakarta.transaction.Transactional;

public class AppointmentConfigRepositoryImpl implements AppointmentConfigRepository {

    private final AppointmentConfigJpaRepository jpaRepository;

    public AppointmentConfigRepositoryImpl(AppointmentConfigJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public boolean isAutonomousSchedulingEnabled() {
        return jpaRepository.findById(1)
                .map(AppointmentConfigEntity::isAutonomousSchedulingEnabled)
                .orElse(true);
    }

    @Override
    @Transactional
    public void setAutonomousSchedulingEnabled(boolean enabled) {
        AppointmentConfigEntity entity = jpaRepository.findById(1)
                .orElseThrow(() -> new IllegalStateException("La configuracion del agendamiento autonomo no está configurada"));
        entity.setAutonomousSchedulingEnabled(enabled);
        jpaRepository.save(entity);
    }
}
