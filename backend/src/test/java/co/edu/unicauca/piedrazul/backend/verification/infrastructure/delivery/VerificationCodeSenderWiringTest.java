package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El código de verificación solo sale por el módulo de notificaciones: no existe otra
 * implementación de {@link VerificationCodeSender} que pueda registrarse y escribir el código en
 * el log.
 */
class VerificationCodeSenderWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = NotificationVerificationCodeSender.class)
    static class DeliveryScan {
        @Bean
        NotificationModuleApi notificationModuleApi() {
            return mock(NotificationModuleApi.class);
        }
    }

    @Test
    void scanningDeliveryPackageRegistersOnlyTheNotificationSender() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeliveryScan.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VerificationCodeSender.class);
                    assertThat(context.getBean(VerificationCodeSender.class))
                            .isInstanceOf(NotificationVerificationCodeSender.class);
                });
    }

    @Test
    void notificationSenderIsTheOnlyImplementationOnTheClasspath() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(VerificationCodeSender.class));

        Set<String> implementations = scanner
                .findCandidateComponents("co.edu.unicauca.piedrazul.backend")
                .stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.toSet());

        assertThat(implementations)
                .containsExactly(NotificationVerificationCodeSender.class.getName());
    }
}
