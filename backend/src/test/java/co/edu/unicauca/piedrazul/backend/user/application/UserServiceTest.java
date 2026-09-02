package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.exception.DoctorRoleRequiredException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	private KeycloakUserService keycloakUserService;

	@InjectMocks
	private UserService userService;

	@Test
	void getSystemUsersShouldMergeDoctorsAndSchedulersWithoutDuplicates() {
		UUID doctorId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID schedulerId = UUID.fromString("22222222-2222-2222-2222-222222222222");

		UserSummary doctor = new UserSummary(doctorId, "doctor01", "Ana", "Lopez",
				"ana@test.com", List.of("DOCTOR"));
		UserSummary scheduler = new UserSummary(schedulerId, "scheduler01", "Luis", "Perez",
				"luis@test.com", List.of("SCHEDULER"));

		when(keycloakUserService.findDoctors()).thenReturn(List.of(doctor, scheduler));
		when(keycloakUserService.getSystemUsers()).thenReturn(List.of(scheduler));

		when(keycloakUserService.getUserRolesByIds(Set.of(doctorId, schedulerId)))
				.thenReturn(Map.of(
						doctorId, List.of(Role.DOCTOR.name(), Role.PATIENT.name()),
						schedulerId, List.of(Role.SCHEDULER.name(), Role.DOCTOR.name())
				));

		Pageable pageable = PageRequest.of(0, 10, Sort.by("firstName").ascending());
		Page<SystemUserResponse> resultPage = userService.getSystemUsers(pageable);

		List<SystemUserResponse> result = resultPage.getContent();

		assertEquals(2, resultPage.getTotalElements());
		assertEquals(2, result.size());

		assertEquals(doctorId, result.get(0).id());
		assertEquals("Ana", result.get(0).firstName());
		assertEquals("Lopez", result.get(0).lastName());
		assertEquals("doctor01", result.get(0).documentId()); // Ajusta a username() o documentId() según tu DTO
		assertEquals(List.of(Role.DOCTOR.name()), result.get(0).roles()); // EXCLUDED_ROLES filtra PATIENT

		assertEquals(schedulerId, result.get(1).id());
		assertEquals("Luis", result.get(1).firstName());
		assertEquals("Perez", result.get(1).lastName());
		assertEquals("scheduler01", result.get(1).documentId());
		assertEquals(List.of(Role.SCHEDULER.name(), Role.DOCTOR.name()), result.get(1).roles());

		verify(keycloakUserService).findDoctors();
		verify(keycloakUserService).getSystemUsers();
		verify(keycloakUserService).getUserRolesByIds(Set.of(doctorId, schedulerId));
	}

	@Test
	void giveDoctorScheduleRoleShouldDelegateWhenUserIsDoctor() {
		UUID doctorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		UserSummary doctor = new UserSummary(doctorId, "doctor02", "Maria", "Gomez",
				"maria@test.com", List.of("DOCTOR"));

		when(keycloakUserService.findUserByUsername("doctor02")).thenReturn(Optional.of(doctor));
		when(keycloakUserService.getUserRoles(doctorId)).thenReturn(List.of(Role.DOCTOR.name()));

		userService.giveDoctorScheduleRole("doctor02");

		verify(keycloakUserService).ensureSchedulerRole(doctorId);
	}

	@Test
	void giveDoctorScheduleRoleShouldThrowWhenUserHasNoDoctorRole() {
		UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		UserSummary user = new UserSummary(userId, "user01", "Pedro", "Ramirez", "pedro@test.com", List.of("PATIENT"));

		when(keycloakUserService.findUserByUsername("user01")).thenReturn(Optional.of(user));
		when(keycloakUserService.getUserRoles(userId)).thenReturn(List.of(Role.PATIENT.name()));

		assertThrows(DoctorRoleRequiredException.class, () -> userService.giveDoctorScheduleRole("user01"));
	}

	@Test
	void revokeDoctorSchedulerRoleShouldDelegateWhenUserIsDoctor() {
		UUID doctorId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		UserSummary doctor = new UserSummary(doctorId, "doctor03", "Sofia", "Torres",
				"sofia@test.com", List.of("DOCTOR","SCHEDULER"));

		when(keycloakUserService.findUserByUsername("doctor03")).thenReturn(Optional.of(doctor));
		when(keycloakUserService.getUserRoles(doctorId)).thenReturn(List.of(Role.DOCTOR.name(), Role.SCHEDULER.name()));

		userService.revokeDoctorSchedulerRole("doctor03");

		verify(keycloakUserService).revokeSchedulerRole(doctorId);
	}

	@Test
	void giveDoctorScheduleRoleShouldThrowWhenUserIsMissing() {
		when(keycloakUserService.findUserByUsername("missing")).thenReturn(Optional.empty());

		assertThrows(UserNotFoundException.class, () -> userService.giveDoctorScheduleRole("missing"));
	}

}