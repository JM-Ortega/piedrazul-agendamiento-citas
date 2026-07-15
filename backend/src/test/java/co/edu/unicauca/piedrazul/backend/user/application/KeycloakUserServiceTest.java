package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakUserServiceTest {

	@Mock
	private KeycloakUserClient keycloakClient;

	@InjectMocks
	private KeycloakUserService keycloakUserService;

	@Test
	void findUserByUsernameShouldMapUserRepresentationToSummary() {
		UserRepresentation representation = userRepresentation(
				"11111111-1111-1111-1111-111111111111",
				"doctor01",
				"Ana",
				"Lopez",
				"ana@test.com"
		);
		when(keycloakClient.findUserByUsername("doctor01")).thenReturn(Optional.of(representation));

		Optional<UserSummary> result = keycloakUserService.findUserByUsername("doctor01");

		assertTrue(result.isPresent());
		assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), result.get().id());
		assertEquals("doctor01", result.get().username());
		assertEquals("Ana", result.get().firstName());
		assertEquals("Lopez", result.get().lastName());
		assertEquals("ana@test.com", result.get().email());
		verify(keycloakClient).findUserByUsername("doctor01");
	}

	@Test
	void findDoctorsShouldMapAllRepresentations() {
		UserRepresentation representation = userRepresentation(
				"22222222-2222-2222-2222-222222222222",
				"doctor02",
				"Luis",
				"Perez",
				"luis@test.com"
		);
		when(keycloakClient.findUsersByRole(Role.DOCTOR)).thenReturn(List.of(representation));

		List<UserSummary> result = keycloakUserService.findDoctors();

		assertEquals(1, result.size());
		assertEquals("doctor02", result.get(0).username());
		assertEquals("Luis", result.get(0).firstName());
		verify(keycloakClient).findUsersByRole(Role.DOCTOR);
	}

	@Test
	void findSchedulersShouldDelegateToClient() {
		when(keycloakClient.findUsersByRole(Role.SCHEDULER)).thenReturn(List.of());

		List<UserSummary> result = keycloakUserService.findSchedulers();

		assertTrue(result.isEmpty());
		verify(keycloakClient).findUsersByRole(Role.SCHEDULER);
	}

	@Test
	void getUserRolesShouldDelegateToClient() {
		UUID userId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		when(keycloakClient.getUserRoles(userId)).thenReturn(List.of(Role.DOCTOR.name(), Role.SCHEDULER.name()));

		List<String> result = keycloakUserService.getUserRoles(userId);

		assertEquals(List.of(Role.DOCTOR.name(), Role.SCHEDULER.name()), result);
		verify(keycloakClient).getUserRoles(userId);
	}

	@Test
	void ensureSchedulerRoleShouldDelegateToClient() {
		UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");

		keycloakUserService.ensureSchedulerRole(userId);

		verify(keycloakClient).assignRoleIfMissing(userId, Role.SCHEDULER);
	}

	@Test
	void revokeSchedulerRoleShouldDelegateToClient() {
		UUID userId = UUID.fromString("55555555-5555-5555-5555-555555555555");

		keycloakUserService.revokeSchedulerRole(userId);

		verify(keycloakClient).revokeRoleIfPresent(userId, Role.SCHEDULER);
	}

	@Test
	void existsByIdShouldDelegateToClient() {
		UUID userId = UUID.fromString("66666666-6666-6666-6666-666666666666");
		when(keycloakClient.existsUser(userId)).thenReturn(true);

		boolean result = keycloakUserService.existsById(userId);

		assertTrue(result);
		verify(keycloakClient).existsUser(userId);
	}

	@Test
	void deactivateUserShouldDelegateToClient() {
		UUID userId = UUID.fromString("77777777-7777-7777-7777-777777777777");

		keycloakUserService.deactivateUser(userId);

		verify(keycloakClient).deactivateUser(userId);
	}

	@Test
	void activateUserShouldDelegateToClient() {
		UUID userId = UUID.fromString("88888888-8888-8888-8888-888888888888");

		keycloakUserService.activateUser(userId);

		verify(keycloakClient).activateUser(userId);
	}

	@Test
	void deleteUserShouldDelegateToClient() {
		UUID userId = UUID.fromString("99999999-9999-9999-9999-999999999999");

		keycloakUserService.deleteUser(userId);

		verify(keycloakClient).deleteUser(userId);
	}

	@Test
	void existsByIdShouldReturnFalseWhenClientDoesNotFindUser() {
		UUID userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		when(keycloakClient.existsUser(userId)).thenReturn(false);

		boolean result = keycloakUserService.existsById(userId);

		assertFalse(result);
		verify(keycloakClient).existsUser(userId);
	}

	private UserRepresentation userRepresentation(String id, String username, String firstName, String lastName, String email) {
		UserRepresentation representation = new UserRepresentation();
		representation.setId(id);
		representation.setUsername(username);
		representation.setFirstName(firstName);
		representation.setLastName(lastName);
		representation.setEmail(email);
		return representation;
	}

}