package co.edu.unicauca.piedrazul.backend.medicalCheckup.api;

import co.edu.unicauca.piedrazul.backend.support.DiagnosticsProbeEndpoints;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller("medicalDiagnosticsProbe")
@ResponseBody
@RequestMapping("/probe/medical")
class ModuleDiagnosticsProbe extends DiagnosticsProbeEndpoints {
}
