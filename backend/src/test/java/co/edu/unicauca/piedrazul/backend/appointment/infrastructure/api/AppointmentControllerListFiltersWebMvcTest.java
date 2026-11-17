package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import co.edu.unicauca.piedrazul.backend.config.security.JwtAuthConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enlace HTTP de los filtros de fecha de {@code GET /api/appointments}: {@code dateTo} se parsea desde
 * el query string igual que {@code date} (yyyy-MM-dd) y llega tal cual al caso de uso; ausente es null.
 */
class AppointmentControllerListFiltersWebMvcTest {

    private static final LocalDate DATE = LocalDate.of(2043, 9, 15);
    private static final LocalDate DATE_TO = LocalDate.of(2043, 9, 20);

    private ListAppointmentsUseCase listAppointmentsUseCase;
    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        listAppointmentsUseCase = mock(ListAppointmentsUseCase.class);
        when(listAppointmentsUseCase.listBy(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PagedResult<>(List.of(), 0, 5, 0, 0));

        AppointmentController controller = new AppointmentController(
                listAppointmentsUseCase,
                mock(CitaDtoMapper.class),
                mock(IsNewPatientUseCase.class),
                mock(UpdateAppointmentStatusUseCase.class),
                mock(CancelAppointmentUseCase.class),
                mock(GetAppointmentStatesUseCase.class),
                mock(UpdateAutonomousSchedulingUseCase.class),
                mock(GetAutonomousSchedulingContidionUseCase.class),
                mock(RegisterUnscheduledAttentionUseCase.class),
                mock(GetDoctorDailyAgendaUseCase.class),
                mock(CountScheduledAppointmentsUseCase.class),
                mock(CheckExistenceByDocAndStateUseCase.class),
                mock(AppointmentSchedulingService.class),
                mock(ManualPatientResolutionStrategy.class),
                mock(AutonomousPatientResolutionStrategy.class),
                mock(PatientConsultPort.class),
                mock(DoctorConfigConsultPort.class));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("realm_access", Map.of("roles", List.of("SCHEDULER")))
                .build();
        authentication = new JwtAuthConverter().convert(jwt);

        // Standalone no instala Spring Security: se resuelve @AuthenticationPrincipal Jwt a mano.
        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                        && Jwt.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return jwt;
            }
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(jwtResolver).build();
    }

    @Test
    void dateAndDateToShouldBeBoundFromIsoQueryStringAndReachTheUseCase() throws Exception {
        mockMvc.perform(get("/api/appointments")
                        .param("date", "2043-09-15").param("dateTo", "2043-09-20").principal(authentication))
                .andExpect(status().isOk());

        verify(listAppointmentsUseCase).listBy(isNull(), isNull(), eq(DATE), eq(DATE_TO), isNull(), any());
    }

    @Test
    void dateToAloneShouldReachTheUseCaseWithDateNull() throws Exception {
        mockMvc.perform(get("/api/appointments")
                        .param("dateTo", "2043-09-20").param("sortDirection", "DESC").principal(authentication))
                .andExpect(status().isOk());

        verify(listAppointmentsUseCase).listBy(isNull(), isNull(), isNull(), eq(DATE_TO), isNull(), any());
    }

    @Test
    void anAbsentDateToShouldReachTheUseCaseAsNull() throws Exception {
        mockMvc.perform(get("/api/appointments").param("date", "2043-09-15").principal(authentication))
                .andExpect(status().isOk());

        verify(listAppointmentsUseCase).listBy(isNull(), isNull(), eq(DATE), isNull(), isNull(), any());
    }

    @Test
    void dateToShouldNotChangeTheDefaultSortOrDirection() throws Exception {
        mockMvc.perform(get("/api/appointments").param("dateTo", "2043-09-20").principal(authentication))
                .andExpect(status().isOk());

        ArgumentCaptor<PageQuery> pageQuery = ArgumentCaptor.forClass(PageQuery.class);
        verify(listAppointmentsUseCase).listBy(any(), any(), any(), eq(DATE_TO), any(), pageQuery.capture());
        assertThat(pageQuery.getValue().sortBy()).isEqualTo("date");
        assertThat(pageQuery.getValue().ascending()).isTrue();
    }
}
