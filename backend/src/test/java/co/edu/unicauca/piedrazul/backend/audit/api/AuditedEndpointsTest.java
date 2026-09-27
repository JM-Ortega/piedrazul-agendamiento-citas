package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.shared.audit.Auditable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fija qué endpoints se auditan y con qué acción. Es la tabla acordada: si alguien agrega,
 * quita o cambia un {@code @Auditable}, este test falla y obliga a revisar la tabla.
 *
 * <p>{@code onlyDenied} solo tiene sentido en endpoints con restricción de rol: sin ella
 * nunca habría un rechazo que registrar. Por eso también se exige el permiso.
 */
class AuditedEndpointsTest {

    private static final String EVERY_OUTCOME = "todos los resultados";
    private static final String ONLY_DENIED = "solo rechazos";

    @Test
    void theAuditedEndpointsAreExactlyTheAgreedOnes() throws Exception {
        TreeMap<String, String> found = new TreeMap<>();
        List<String> withoutRoleRestriction = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                Auditable auditable = method.getAnnotation(Auditable.class);
                if (auditable == null) continue;

                String key = controller.getSimpleName() + "." + method.getName();
                found.put(key, auditable.action() + " · " + (auditable.onlyDenied() ? ONLY_DENIED : EVERY_OUTCOME));

                boolean restricted = method.isAnnotationPresent(PreAuthorize.class)
                        || controller.isAnnotationPresent(PreAuthorize.class);
                if (auditable.onlyDenied() && !restricted) {
                    withoutRoleRestriction.add(key);
                }
            }
        }

        assertThat(withoutRoleRestriction)
                .as("endpoints con onlyDenied pero sin @PreAuthorize: nunca registrarían nada")
                .isEmpty();

        TreeMap<String, String> agreed = new TreeMap<>();
        agreed.put("AppointmentController.scheduleAppointment", "CITA_AGENDADA · " + ONLY_DENIED);
        agreed.put("AppointmentController.registerUnscheduledAttention", "CONTROL_MEDICO_CREADO · " + ONLY_DENIED);
        agreed.put("AppointmentController.markAppointmentAsAttended", "CONTROL_MEDICO_CREADO · " + ONLY_DENIED);
        agreed.put("PatientController.update", "PACIENTE_MODIFICADO · " + ONLY_DENIED);
        agreed.put("PatientController.updateMe", "PACIENTE_MODIFICADO · " + ONLY_DENIED);
        agreed.put("UserController.createUser", "USUARIO_CREADO · " + ONLY_DENIED);
        agreed.put("UserController.giveScheduleRole", "ROL_ASIGNADO · " + ONLY_DENIED);
        agreed.put("UserController.revokeSchedulerRole", "ROL_REVOCADO · " + ONLY_DENIED);
        agreed.put("UserController.activatePatientUser", "USUARIO_ACTIVADO · " + ONLY_DENIED);
        agreed.put("UserController.deactivatePatientUser", "USUARIO_DESACTIVADO · " + ONLY_DENIED);
        agreed.put("DoctorController.enableDoctor", "ROL_ASIGNADO · " + ONLY_DENIED);
        agreed.put("DoctorController.disableDoctor", "ROL_REVOCADO · " + ONLY_DENIED);
        // Sin evento de éxito: el aspecto registra todos los resultados.
        agreed.put("MedicalCheckupController.getByPatient", "CONTROL_MEDICO_CONSULTADO · " + EVERY_OUTCOME);
        agreed.put("MedicalCheckupController.updateCheckup", "CONTROL_MEDICO_MODIFICADO · " + EVERY_OUTCOME);

        assertThat(found).isEqualTo(agreed);
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> classes = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("co.edu.unicauca.piedrazul.backend")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        return classes;
    }
}
