package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import co.edu.unicauca.piedrazul.backend.jackson.normalization.NormalizeName;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.ConfirmLinkUserAccountRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.CreatePatientWithUserRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Centinela de sanitización de los cuerpos JSON.
 *
 * <p>Primero comprueba que el conversor JSON que usa Spring MVC de verdad aplica {@link Sanitize}
 * y {@link NormalizeName}: si las anotaciones fueran de otra versión de Jackson, se ignorarían sin
 * ningún error. Después exige que todo campo de texto de un {@code @RequestBody} esté sanitizado
 * o tenga un formato que no admita HTML.
 */
class RequestBodySanitizationTest {

    private static final String BASE_PACKAGE = "co.edu.unicauca.piedrazul.backend";

    /**
     * Campos de texto que no se sanitizan a propósito. Cambiar esta lista es una decisión de
     * seguridad: cada entrada debe explicar por qué alterar el valor sería un error.
     */
    private static final Set<String> NOT_SANITIZED_ON_PURPOSE = Set.of(
            // Las contraseñas se guardan tal cual en el proveedor de identidad y nunca se muestran.
            "password"
    );

    // ---- el conversor real aplica las anotaciones ----

    @Test
    void theJsonConverterOfSpringMvcSanitizesAnnotatedFields() throws Exception {
        ConfirmLinkUserAccountRequest request = readWithSpringMvc(
                "{\"identification\":\"<script>alert(1)</script>1061234567\",\"code\":\"<b>ABC123</b>\"}",
                ConfirmLinkUserAccountRequest.class);

        assertThat(request.getIdentification()).isEqualTo("1061234567");
        assertThat(request.getCode()).isEqualTo("<b>ABC123</b>");
    }

    @Test
    void theJsonConverterOfSpringMvcSanitizesAndNormalizesNames() throws Exception {
        CreatePatientWithUserRequest request = readWithSpringMvc(
                "{\"firstName\":\"  aNA   <script>alert(1)</script>maría \"}",
                CreatePatientWithUserRequest.class);

        assertThat(request.getFirstName()).isEqualTo("Ana María");
    }

    @Test
    void passwordsAreNotAltered() throws Exception {
        CreatePatientWithUserRequest request = readWithSpringMvc(
                "{\"password\":\"<b>Clave&Segura</b>\"}",
                CreatePatientWithUserRequest.class);

        assertThat(request.getPassword()).isEqualTo("<b>Clave&Segura</b>");
    }

    /** Lee el JSON con el mismo conversor que Spring MVC usa para un {@code @RequestBody}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T readWithSpringMvc(String json, Class<T> type) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class,
                        WebMvcAutoConfiguration.class))
                .run(context -> {
                    RequestMappingHandlerAdapter adapter = context.getBean(RequestMappingHandlerAdapter.class);
                    HttpMessageConverter converter = adapter.getMessageConverters().stream()
                            .filter(candidate -> candidate.canRead(type, MediaType.APPLICATION_JSON))
                            .findFirst()
                            .orElseThrow();

                    MockHttpInputMessage message = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
                    message.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    try {
                        result.set(converter.read(type, message));
                    } catch (Exception e) {
                        failure.set(e);
                    }
                });

        if (failure.get() != null) {
            throw failure.get();
        }
        return (T) result.get();
    }

    // ---- cobertura de los cuerpos JSON ----

    @Test
    void everyTextFieldOfARequestBodyIsSanitizedOrRestricted() {
        Set<String> unprotected = new TreeSet<>();
        for (Class<?> body : requestBodies()) {
            collectUnprotectedFields(body, new HashSet<>(), unprotected);
        }

        assertThat(unprotected)
                .as("campos de texto de un @RequestBody sin @Sanitize ni un @Pattern o @Email que "
                        + "impida el HTML. Agrega @Sanitize o, si alterar el valor sería un error, "
                        + "agrégalo a NOT_SANITIZED_ON_PURPOSE explicando por qué")
                .isEmpty();
    }

    @Test
    void theScanFindsTheRequestBodies() {
        assertThat(requestBodies())
                .as("si el escaneo no encuentra cuerpos, el test anterior pasaría sin revisar nada")
                .contains(ConfirmLinkUserAccountRequest.class, CreatePatientWithUserRequest.class);
    }

    private static Set<Class<?>> requestBodies() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<Class<?>> bodies = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
            for (Method method : controller.getDeclaredMethods()) {
                for (int i = 0; i < method.getParameterCount(); i++) {
                    MethodParameter parameter = new MethodParameter(method, i);
                    if (parameter.hasParameterAnnotation(RequestBody.class)) {
                        bodies.addAll(ownTypesIn(ResolvableType.forMethodParameter(parameter)));
                    }
                }
            }
        }
        return bodies;
    }

    private static void collectUnprotectedFields(Class<?> type, Set<Class<?>> visited, Set<String> unprotected) {
        if (!visited.add(type)) {
            return;
        }

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                if (field.getType() == String.class) {
                    if (!isProtected(field)) {
                        unprotected.add(type.getSimpleName() + "." + field.getName());
                    }
                    continue;
                }

                if (isTextCollection(field)) {
                    unprotected.add(type.getSimpleName() + "." + field.getName()
                            + " (colección de textos: @Sanitize no aplica a cada elemento)");
                    continue;
                }

                for (Class<?> nested : ownTypesIn(ResolvableType.forField(field))) {
                    collectUnprotectedFields(nested, visited, unprotected);
                }
            }
        }
    }

    private static boolean isProtected(Field field) {
        return field.isAnnotationPresent(Sanitize.class)
                || field.isAnnotationPresent(NormalizeName.class)
                || field.isAnnotationPresent(Pattern.class)
                || field.isAnnotationPresent(Email.class)
                || NOT_SANITIZED_ON_PURPOSE.contains(field.getName());
    }

    private static boolean isTextCollection(Field field) {
        ResolvableType type = ResolvableType.forField(field);
        if (type.isArray()) {
            return type.getComponentType().resolve() == String.class;
        }
        return Collection.class.isAssignableFrom(field.getType())
                && type.getGeneric(0).resolve() == String.class;
    }

    /** Las clases propias del proyecto dentro de un tipo, incluidos los genéricos ({@code List<Dto>}). */
    private static Set<Class<?>> ownTypesIn(ResolvableType type) {
        Set<Class<?>> found = new HashSet<>();
        Class<?> resolved = type.resolve();
        if (resolved != null && !resolved.isEnum() && resolved.getName().startsWith(BASE_PACKAGE)) {
            found.add(resolved);
        }
        if (type.isArray()) {
            found.addAll(ownTypesIn(type.getComponentType()));
        }
        for (ResolvableType generic : type.getGenerics()) {
            found.addAll(ownTypesIn(generic));
        }
        return found;
    }
}
