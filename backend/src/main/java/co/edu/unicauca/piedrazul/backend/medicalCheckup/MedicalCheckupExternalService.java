package co.edu.unicauca.piedrazul.backend.medicalCheckup;

import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.internal.MedicalCheckupRequest;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.intput.CheckupUpdateRequest;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.output.MedicalCheckupResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

//Define qué puede hacer el módulo sin decir cómo lo hace
public interface MedicalCheckupExternalService {

    //Expone los servicios que usarán los controllers
    //El controller no necesita saber cómo funciona el servicio
    //Solo necesita saber qué puede pedirle

    void registerClinicalHistory(MedicalCheckupRequest request);

    Page<MedicalCheckupResponse> getHistoryByPatient(UUID idPatient, Pageable pageable);

    MedicalCheckupResponse updateCheckUp(UUID idClinicalHistory, CheckupUpdateRequest request);
}

