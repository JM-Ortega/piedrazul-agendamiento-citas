package co.edu.unicauca.piedrazul.backend.clinicalHistory;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input.ClinicalHistoryRequest;

//Define qué puede hacer el módulo sin decir cómo lo hace
public interface ClinicalHistoryExternalService {

    //Expone los servicios que usarán los controllers
    //El controller no necesita saber cómo funciona el servicio
    //Solo necesita saber qué puede pedirle

    void registerClinicalHistory(ClinicalHistoryRequest request);
}

