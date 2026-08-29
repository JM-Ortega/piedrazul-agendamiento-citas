package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import co.edu.unicauca.piedrazul.backend.appointment.exception.AttentionAlreadyAssignedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.DocumentMismatchException;

import java.time.LocalDateTime;
import java.util.UUID;

public class UnscheduledAttention {

    private final UUID id;
    private final UUID idDoctor;
    private UUID idPatient;
    private final String documentNumber;
    private final String description;
    private final LocalDateTime date;

    public UnscheduledAttention(UUID id, UUID idDoctor, UUID idPatient, String documentNumber, String description, LocalDateTime date) {
        this.id = id;
        this.idDoctor = idDoctor;
        this.idPatient = idPatient;
        this.documentNumber = documentNumber;
        this.description = description;
        this.date = date;
    }

    public static UnscheduledAttention register(UUID id, UUID idDoctor, UUID idPatient, String documentNumber, String description, LocalDateTime date){
        if(documentNumber == null || documentNumber.isBlank()){
            throw new IllegalArgumentException("El numero de documento es obligatorio");
        }
        if(description == null || description.isBlank()){
            throw new IllegalArgumentException("La descripción es obligatoria");
        }
        return new UnscheduledAttention(null, idDoctor, null, documentNumber, description, date);
    }

    public static UnscheduledAttention reconstruct(UUID id, UUID idDoctor, UUID idPatient,
                                                   String documentNumber, String description, LocalDateTime attendedAt) {
        return new UnscheduledAttention(id, idDoctor, idPatient, documentNumber, description, attendedAt);
    }

    public void assignPatient(UUID idPatient, String realDocumentNumber){
        if(this.idPatient == null){
            throw new AttentionAlreadyAssignedException("Este control ya tiene un paciente asignado");
        }
        if(!this.documentNumber.equals(realDocumentNumber)){
            throw new DocumentMismatchException(
                    "El número de documento del paciente no coincide con el registrado en el control"
            );
        }
        this.idPatient = idPatient;
    }

    public boolean isAssigned(){
        return this.idPatient != null;
    }


    public UUID getId() {return id;}
    public UUID getIdDoctor() {return idDoctor;}
    public UUID getIdPatient() {return idPatient;}
    public String getDocumentNumber() {return documentNumber;}
    public String getDescription() {return description;}
    public LocalDateTime getDate() {return date;}
}
