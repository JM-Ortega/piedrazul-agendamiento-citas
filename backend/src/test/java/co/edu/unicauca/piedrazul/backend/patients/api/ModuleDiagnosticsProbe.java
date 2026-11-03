package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.support.DiagnosticsProbeEndpoints;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller("patientsDiagnosticsProbe")
@ResponseBody
@RequestMapping("/probe/patients")
class ModuleDiagnosticsProbe extends DiagnosticsProbeEndpoints {
}
