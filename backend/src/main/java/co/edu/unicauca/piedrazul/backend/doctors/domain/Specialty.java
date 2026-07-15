package co.edu.unicauca.piedrazul.backend.doctors.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter

@Entity
@Table(name = "specialty", schema = "piedrazul")
public class Specialty {

    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

}
