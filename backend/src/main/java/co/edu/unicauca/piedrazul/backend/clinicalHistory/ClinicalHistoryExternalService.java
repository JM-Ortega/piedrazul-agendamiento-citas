package co.edu.unicauca.piedrazul.backend.clinicalHistory;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.internal.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

//Define qué puede hacer el módulo sin decir cómo lo hace
public interface ClinicalHistoryExternalService {

    //Expone los servicios que usarán los controllers
    //El controller no necesita saber cómo funciona el servicio
    //Solo necesita saber qué puede pedirle

    void registerClinicalHistory(ClinicalHistoryRequest request);

    Page<ClinicalHistoryResponse> getHistoryByPatient(UUID idPatient, Pageable pageable);
}

