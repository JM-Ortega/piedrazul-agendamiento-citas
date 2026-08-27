package co.edu.unicauca.piedrazul.backend.clinicalHistory;


import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import java.util.List;
import java.util.UUID;

//Define qué puede hacer el módulo sin decir cómo lo hace
public interface ClinicalHistoryExternalService {

    //Expone los servicios que usarán los controllers
    //El controller no necesita saber cómo funciona el servicio
    //Solo necesita saber qué puede pedirle

    ClinicalHistoryResponse registerClinicalHistory(ClinicalHistoryRequest request);

    List<ClinicalHistoryResponse> getHistoryByPatient(UUID idPatient);


}

