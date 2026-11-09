package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.support.DiagnosticsProbeEndpoints;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller("appointmentDiagnosticsProbe")
@ResponseBody
@RequestMapping("/probe/appointment")
class ModuleDiagnosticsProbe extends DiagnosticsProbeEndpoints {
}
