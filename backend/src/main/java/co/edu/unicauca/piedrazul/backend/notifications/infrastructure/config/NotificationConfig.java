package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.config;

import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.application.*;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.template.NotificationTemplateProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
            NotificationDispatchStore dispatchStore,
            ProviderRegistry providerRegistry,
            TemplateRenderer templateRenderer,
            RetryPolicy retryPolicy,
            ChannelFallbackPolicy fallbackPolicy,
            Validator validator,
            AppointmentEligibility appointmentEligibility,
            @Value("${notifications.console.show-sensitive-content:false}") boolean showSensitiveContent,
            @Qualifier("notificationClock") Clock clock
    ) {
        return new NotificationDispatcherService(
                dispatchStore,
                providerRegistry,
                templateRenderer,
                retryPolicy,
                fallbackPolicy,
                new RecipientEmailPolicy(validator),
                appointmentEligibility,
                showSensitiveContent,
                clock
        );
    }

    @Bean
    public NotificationOrchestrator notificationOrchestrator(
            NotificationRepository notificationRepository,
            NotificationFactory notificationFactory,
            NotificationSchedulerService schedulerService,
            ChannelFallbackPolicy fallbackPolicy
    ) {
        return new NotificationOrchestrator(
                notificationRepository,
                notificationFactory,
                schedulerService,
                fallbackPolicy
        );
    }

    @Bean
    public OtpDeliveryCapability otpDeliveryCapability(
            ProviderRegistry providerRegistry,
            ChannelFallbackPolicy fallbackPolicy,
            @Value("${notifications.console.show-sensitive-content:false}") boolean showSensitiveContent,
            Validator validator
    ) {
        return new OtpDeliveryCapability(
                providerRegistry,
                fallbackPolicy,
                showSensitiveContent,
                validator
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
            OtpDeliveryCapability otpDeliveryCapability,
            @Qualifier("notificationClock") Clock clock
    ) {
        return new NotificationModuleApiImpl(
                orchestrator,
                otpDeliveryCapability,
                clock
        );
    }
}