package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.DuplicateNotificationProviderException;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationProviderNotFoundException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProviderRegistry {

    private final List<NotificationProvider> providers;

    public ProviderRegistry(
            List<NotificationProvider> providers
    ) {
        validateNoDuplicateChannels(providers);
        this.providers = providers;
    }

    public NotificationProvider getProvider(
            NotificationChannel channel
    ) {
        return providers.stream()
                .filter(provider -> provider.channel() == channel)
                .findFirst()
                .orElseThrow(() ->
                        new NotificationProviderNotFoundException(channel)
                );
    }

    public boolean hasProvider(NotificationChannel channel) {
        return providers.stream().anyMatch(p -> p.channel() == channel);
    }

    private void validateNoDuplicateChannels(
            List<NotificationProvider> providers
    ) {
        Map<NotificationChannel, List<NotificationProvider>> grouped =
                providers.stream()
                        .collect(Collectors.groupingBy(
                                NotificationProvider::channel
                        ));

        grouped.forEach((channel, channelProviders) -> {
            if (channelProviders.size() > 1) {
                throw new DuplicateNotificationProviderException(channel);
            }
        });
    }
}