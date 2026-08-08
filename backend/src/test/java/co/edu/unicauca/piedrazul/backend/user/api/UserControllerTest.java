package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemUserResponse;
import co.edu.unicauca.piedrazul.backend.user.application.CreateAccountUseCase;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

	@Mock
	private CreateAccountUseCase createAccountUseCase;

	@Mock
	private UserService userService;

	@InjectMocks
	private UserController userController;

	@Test
	void getSystemUsersReturnsOkWithUsersFromService() {
		List<SystemUserResponse> expectedUsers = List.of(
				new SystemUserResponse(
						UUID.fromString("11111111-1111-1111-1111-111111111111"),
						"Ana",
						"Perez",
						"1001",
						List.of(Role.DOCTOR.name())));
		Pageable pageable = PageRequest.of(0, 9, Sort.by(Sort.Direction.ASC, "firstName"));
		when(userService.getSystemUsers(pageable))
				.thenReturn(new PageImpl<>(expectedUsers, pageable, expectedUsers.size()));

		var response = userController.getSystemUsers(pageable);

		assertEquals(HttpStatus.OK, response.getStatusCode());
		PageResponse<SystemUserResponse> body = response.getBody();
		assertEquals(expectedUsers, body.content());
		assertEquals(0, body.page());
		assertEquals(1, body.totalPages());
		assertEquals(expectedUsers.size(), body.totalElements());
		verify(userService).getSystemUsers(pageable);
	}

	@Test
	void createUserDelegatesToCreateAccountUseCaseAndReturnsNoContent() {
		CreateSystemUserPayload payload = buildPayload(List.of(Role.ADMIN));

		var response = userController.createUser(payload);

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
		verify(createAccountUseCase).execute(payload);
	}

	@Test
	void createUserDelegatesWhenOnlyPatientRoleIsProvided() {
		CreateSystemUserPayload payload = buildPayload(List.of(Role.PATIENT));

		var response = userController.createUser(payload);

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
		verify(createAccountUseCase).execute(payload);
	}

	@Test
	void giveScheduleRoleDelegatesToUserService() {
		userController.giveScheduleRole("doctor-01");

		verify(userService).giveDoctorScheduleRole("doctor-01");
	}

	@Test
	void revokeSchedulerRoleDelegatesToUserService() {
		userController.revokeSchedulerRole("doctor-01");

		verify(userService).revokeDoctorSchedulerRole("doctor-01");
	}

	private CreateSystemUserPayload buildPayload(List<Role> roles) {
		return new CreateSystemUserPayload(
				new CreateSystemUserRequest(
						"1001",
						IdentificationType.CEDULA,
						"Ana",
						"Perez",
						"ana.perez@example.com",
						"3206228173",
						"secret123"),
				null,
				null,
				roles);
	}

}
