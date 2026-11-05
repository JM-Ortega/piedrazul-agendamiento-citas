package co.edu.unicauca.piedrazul.backend.statistics.api;

import co.edu.unicauca.piedrazul.backend.support.DiagnosticsProbeEndpoints;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller("statisticsDiagnosticsProbe")
@ResponseBody
@RequestMapping("/probe/statistics")
class ModuleDiagnosticsProbe extends DiagnosticsProbeEndpoints {
}
