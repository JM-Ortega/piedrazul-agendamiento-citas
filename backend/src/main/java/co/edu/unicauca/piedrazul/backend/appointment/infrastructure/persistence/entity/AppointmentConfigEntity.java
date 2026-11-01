package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "appointment_config")
public class AppointmentConfigEntity {

    @Id
    private int id;

    @Column(name = "autonomous_scheduling_enabled", nullable = false)
    private boolean isAutonomousSchedulingEnabled;
}
