package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.EventRepresentation;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAuditCollectorTest {

    private static final Instant NOW = Instant.parse("2026-10-03T15:00:00Z");
    private static final long T0 = NOW.minus(Duration.ofMinutes(10)).toEpochMilli();

    private static final String DOCTOR = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String DOCTOR_WHO_IS_ALSO_PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String DELETED = "44444444-4444-4444-4444-444444444444";

    private KeycloakUserClient client;
    private ApplicationEventPublisher publisher;
    private LoginAuditCollector collector;

    @BeforeEach
    void setUp() {
        client = mock(KeycloakUserClient.class);
        publisher = mock(ApplicationEventPublisher.class);
        collector = new LoginAuditCollector(client, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

        when(client.getUserRoles(DOCTOR)).thenReturn(List.of("default-roles-piedrazul", "SCHEDULER", "DOCTOR"));
        when(client.getUserRoles(PATIENT)).thenReturn(List.of("default-roles-piedrazul", "PATIENT"));
        when(client.getUserRoles(DOCTOR_WHO_IS_ALSO_PATIENT)).thenReturn(List.of("PATIENT", "DOCTOR"));
        when(client.getUserRoles(DELETED)).thenThrow(new NotFoundException());
        when(client.findLoginEvents(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
    }

    private static EventRepresentation login(String id, String userId, String sessionId, long time) {
        EventRepresentation event = new EventRepresentation();
        event.setId(id);
        event.setType("LOGIN");
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setTime(time);
        return event;
    }

    private static EventRepresentation failure(String id, String userId, String error, long time) {
        EventRepresentation event = new EventRepresentation();
        event.setId(id);
        event.setType("LOGIN_ERROR");
        event.setUserId(userId);
        event.setError(error);
        event.setTime(time);
        return event;
    }

    private void keycloakReturns(EventRepresentation... events) {
        when(client.findLoginEvents(anyLong(), anyInt(), anyInt())).thenReturn(List.of(events));
    }

    private List<LoginAttemptedEvent> published(int expected) {
        ArgumentCaptor<LoginAttemptedEvent> captor = ArgumentCaptor.forClass(LoginAttemptedEvent.class);
        verify(publisher, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    // ---- qué se publica ----

    @Test
    void aFailedAttemptOfStaffIsPublishedWithItsReason() {
        keycloakReturns(failure("e1", DOCTOR, "invalid_user_credentials", T0));

        collector.collect();

        LoginAttemptedEvent event = published(1).getFirst();
        assertThat(event.userId()).isEqualTo(DOCTOR);
        assertThat(event.successful()).isFalse();
        assertThat(event.error()).isEqualTo("invalid_user_credentials");
        assertThat(event.keycloakEventId()).isEqualTo("e1");
        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochMilli(T0));
    }

    @Test
    void theRolesAreTheBusinessRolesSorted() {
        keycloakReturns(login("e1", DOCTOR, "s1", T0));

        collector.collect();

        assertThat(published(1).getFirst().roles()).isEqualTo("[DOCTOR, SCHEDULER]");
    }

    @Test
    void onlyTheFirstLoginOfEachSessionIsPublished() {
        // El check-sso del frontend genera un LOGIN en cada recarga, con la misma sesión.
        keycloakReturns(
                login("e1", DOCTOR, "s1", T0),
                login("e2", DOCTOR, "s1", T0 + 1_000),
                login("e3", DOCTOR, "s1", T0 + 2_000),
                login("e4", DOCTOR, "s2", T0 + 3_000));

        collector.collect();

        assertThat(published(2)).extracting(LoginAttemptedEvent::keycloakEventId).containsExactly("e1", "e4");
    }

    @Test
    void aSessionPublishedInAPreviousRunIsNotPublishedAgain() {
        keycloakReturns(login("e1", DOCTOR, "s1", T0));
        collector.collect();

        keycloakReturns(login("e2", DOCTOR, "s1", T0 + 60_000));
        collector.collect();

        assertThat(published(1)).extracting(LoginAttemptedEvent::keycloakEventId).containsExactly("e1");
    }

    @Test
    void usersWhoAreOnlyPatientsAreSkipped() {
        keycloakReturns(
                login("e1", PATIENT, "s1", T0),
                failure("e2", PATIENT, "invalid_user_credentials", T0 + 1));

        collector.collect();

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void patientsWhoAlsoHaveAStaffRoleArePublished() {
        keycloakReturns(login("e1", DOCTOR_WHO_IS_ALSO_PATIENT, "s1", T0));

        collector.collect();

        assertThat(published(1).getFirst().roles()).isEqualTo("[DOCTOR, PATIENT]");
    }

    @Test
    void attemptsWithAnUnknownOrDeletedUserAreSkipped() {
        keycloakReturns(
                failure("e1", null, "user_not_found", T0),
                failure("e2", DELETED, "invalid_user_credentials", T0 + 1));

        collector.collect();

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void theRolesOfAUserAreLookedUpOncePerRun() {
        keycloakReturns(
                failure("e1", DOCTOR, "invalid_user_credentials", T0),
                failure("e2", DOCTOR, "invalid_user_credentials", T0 + 1),
                login("e3", DOCTOR, "s1", T0 + 2));

        collector.collect();

        verify(client, times(1)).getUserRoles(DOCTOR);
    }

    // ---- desde dónde se lee ----

    @Test
    void theFirstRunLooksBackOneDay() {
        collector.collect();

        verify(client).findLoginEvents(eq(NOW.minus(Duration.ofDays(1)).toEpochMilli()), eq(0), anyInt());
    }

    @Test
    void theNextRunContinuesFromTheLastEventWithoutRepublishingIt() {
        keycloakReturns(failure("e1", DOCTOR, "invalid_user_credentials", T0));
        collector.collect();

        // Keycloak devuelve otra vez el evento del borde porque el filtro es inclusivo.
        keycloakReturns(
                failure("e1", DOCTOR, "invalid_user_credentials", T0),
                failure("e2", DOCTOR, "invalid_user_credentials", T0 + 5_000));
        collector.collect();

        verify(client).findLoginEvents(eq(T0), eq(0), anyInt());
        assertThat(published(2)).extracting(LoginAttemptedEvent::keycloakEventId).containsExactly("e1", "e2");
    }

    @Test
    void fullPagesAreFollowedByTheNextPage() {
        int pageSize = LoginAuditCollector.PAGE_SIZE;
        List<EventRepresentation> firstPage = IntStream.range(0, pageSize)
                .mapToObj(i -> failure("e" + i, DOCTOR, "invalid_user_credentials", T0 + i))
                .toList();
        long from = NOW.minus(Duration.ofDays(1)).toEpochMilli();
        when(client.findLoginEvents(from, 0, pageSize)).thenReturn(firstPage);
        when(client.findLoginEvents(from, pageSize, pageSize))
                .thenReturn(List.of(failure("last", DOCTOR, "invalid_user_credentials", T0 + pageSize)));

        collector.collect();

        assertThat(published(pageSize + 1)).last()
                .extracting(LoginAttemptedEvent::keycloakEventId).isEqualTo("last");
    }

    @Test
    void whenKeycloakFailsNothingIsPublishedAndTheSameRangeIsReadAgain() {
        int pageSize = LoginAuditCollector.PAGE_SIZE;
        List<EventRepresentation> firstPage = new ArrayList<>();
        for (int i = 0; i < pageSize; i++) {
            firstPage.add(failure("e" + i, DOCTOR, "invalid_user_credentials", T0 + i));
        }
        long from = NOW.minus(Duration.ofDays(1)).toEpochMilli();
        when(client.findLoginEvents(from, 0, pageSize)).thenReturn(firstPage);
        when(client.findLoginEvents(from, pageSize, pageSize)).thenThrow(new RuntimeException("Keycloak caído"));

        assertThatThrownBy(() -> collector.collect()).hasMessage("Keycloak caído");
        verify(publisher, never()).publishEvent(any(Object.class));

        doReturn(List.of()).when(client).findLoginEvents(from, pageSize, pageSize);
        collector.collect();

        verify(client, times(2)).findLoginEvents(from, 0, pageSize);
        published(pageSize);
    }
}
