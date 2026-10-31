package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.shared.audit.AuditTargetType;
import co.edu.unicauca.piedrazul.backend.shared.audit.Auditable;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El filtro {@code targetEntityType} solo ofrece los tipos de {@link AuditTargetType}. Un
 * {@code @Auditable} con un tipo fuera del catálogo grabaría registros que el frontend no
 * podría filtrar.
 */
class AuditTargetTypeCatalogTest {

    @Test
    void everyAuditedEndpointUsesATargetTypeFromTheCatalog() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        TreeMap<String, String> outsideTheCatalog = new TreeMap<>();
        for (var definition : scanner.findCandidateComponents("co.edu.unicauca.piedrazul.backend")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                Auditable auditable = method.getAnnotation(Auditable.class);
                if (auditable == null || auditable.targetEntityType().isEmpty()) continue;
                if (!declaredTypes().contains(auditable.targetEntityType())) {
                    outsideTheCatalog.put(controller.getSimpleName() + "." + method.getName(),
                            auditable.targetEntityType());
                }
            }
        }

        assertThat(outsideTheCatalog).as("tipos de objeto afectado que no están en AuditTargetType").isEmpty();
    }

    /** Los valores de las constantes de {@link AuditTargetType}. */
    private static Set<String> declaredTypes() throws IllegalAccessException {
        Set<String> types = new HashSet<>();
        for (Field field : AuditTargetType.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                types.add((String) field.get(null));
            }
        }
        return types;
    }
}
