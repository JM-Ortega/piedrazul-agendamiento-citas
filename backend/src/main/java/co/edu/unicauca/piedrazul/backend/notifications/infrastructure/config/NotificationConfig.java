package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.application.*;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.template.NotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid.SendGridProperties;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

@Configuration
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(SendGridProperties.class)
public class NotificationConfig {

    @Bean
    public ChannelFallbackPolicy channelFallbackPolicy() {
        return new ChannelFallbackPolicy();
    }

    @Bean
    public RetryPolicy retryPolicy() {
        return new RetryPolicy();
    }

    @Bean
    public ReminderScheduleCalculator reminderScheduleCalculator(
            @Value("${app.timezone:America/Bogota}") String timezone
    ) {
        return new ReminderScheduleCalculator(
                ZoneId.of(timezone)
        );
    }

    @Bean
    public NotificationTemplateProvider notificationTemplateProvider() {
        return new InMemoryNotificationTemplateProvider();
    }

    @Bean
    public TemplateRenderer templateRenderer(
            NotificationTemplateProvider templateProvider
    ) {
        return new TemplateRenderer(templateProvider);
    }

    @Bean
    public ConsoleNotificationProvider consoleNotificationProvider(
            @Value("${notifications.console.show-sensitive-content:false}") boolean showSensitiveContent
    ) {
        return new ConsoleNotificationProvider(showSensitiveContent);
    }

    @Bean
    public ProviderRegistry providerRegistry(
            List<NotificationProvider> providers
    ) {
        return new ProviderRegistry(providers);
    }

    @Bean
    public NotificationFactory notificationFactory() {
        return new NotificationFactory();
    }

    @Bean
    public NotificationSchedulerService notificationSchedulerService(
            NotificationFactory notificationFactory,
            NotificationScheduleRepository scheduleRepository
    ) {
        return new NotificationSchedulerService(
                notificationFactory,
                scheduleRepository
        );
    }

    @Bean
    public NotificationDispatcherService notificationDispatcherService(
            NotificationRepository notificationRepository,
            NotificationScheduleRepository scheduleRepository,
            NotificationAttemptRepository attemptRepository,
            ProviderRegistry providerRegistry,
            TemplateRenderer templateRenderer,
            RetryPolicy retryPolicy,
            ChannelFallbackPolicy fallbackPolicy
    ) {
        return new NotificationDispatcherService(
                notificationRepository,
                scheduleRepository,
                attemptRepository,
                providerRegistry,
                templateRenderer,
                retryPolicy,
                fallbackPolicy
        );
    }

    @Bean
    public NotificationOrchestrator notificationOrchestrator(
            NotificationRepository notificationRepository,
            NotificationScheduleRepository scheduleRepository,
            NotificationFactory notificationFactory,
            NotificationSchedulerService schedulerService,
            ChannelFallbackPolicy fallbackPolicy
    ) {
        return new NotificationOrchestrator(
                notificationRepository,
                scheduleRepository,
                notificationFactory,
                schedulerService,
                fallbackPolicy
        );
    }

    @Bean("notificationClock")
    public Clock notificationClock(
            @Value("${app.timezone:America/Bogota}") String timezone
    ) {
        return Clock.system(ZoneId.of(timezone));
    }

    @Bean
    public NotificationModuleApi notificationModuleApi(
            NotificationOrchestrator orchestrator,
            @Qualifier("notificationClock") Clock clock
    ) {
        return new NotificationModuleApiImpl(
                orchestrator,
                clock
        );
    }
}