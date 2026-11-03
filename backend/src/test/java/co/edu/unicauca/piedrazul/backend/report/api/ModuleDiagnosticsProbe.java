package co.edu.unicauca.piedrazul.backend.report.api;

import co.edu.unicauca.piedrazul.backend.support.DiagnosticsProbeEndpoints;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller("reportDiagnosticsProbe")
@ResponseBody
@RequestMapping("/probe/report")
class ModuleDiagnosticsProbe extends DiagnosticsProbeEndpoints {
}
