package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Prueba determinista (sin Postgres) del orden que {@link AppointmentRepositoryImpl} le pide a la BD:
 * la paginación solo es estable si el último criterio de orden es único ({@code idAppointment}).
 */
@ExtendWith(MockitoExtension.class)
class AppointmentRepositoryImplTest {

    private static final String ID = "idAppointment";

    @Mock
    private AppointmentJpaRepository jpaRepository;
    @Mock
    private AppointmentMapper mapper;

    private AppointmentRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new AppointmentRepositoryImpl(jpaRepository, mapper);
        Page<AppointmentEntity> emptyPage = new PageImpl<>(List.of());
        lenient().when(jpaRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(emptyPage);
    }

    private Sort capturedSort() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jpaRepository).findAll(any(Specification.class), captor.capture());
        return captor.getValue().getSort();
    }

    @ParameterizedTest(name = "sortBy={0} ascending={1}")
    @CsvSource({
            "date,true", "date,false",
            "appointmentState,true", "appointmentState,false",
            "idDoctor,true", "idDoctor,false",
            "idPatient,true", "idPatient,false"
    })
    void listByShouldEndTheSortWithTheUniqueIdAndKeepThePreviousOrder(String sortBy, boolean ascending) {
        repository.listBy(null, null, null, null, new PageQuery(0, 5, sortBy, ascending));

        Sort sort = capturedSort();
        Sort.Direction requested = ascending ? Sort.Direction.ASC : Sort.Direction.DESC;
        List<Sort.Order> expected = "date".equals(sortBy)
                ? List.of(new Sort.Order(requested, "date"),
                        Sort.Order.asc("startTime"), Sort.Order.asc(ID))
                : List.of(new Sort.Order(requested, sortBy),
                        Sort.Order.asc("date"), Sort.Order.asc("startTime"), Sort.Order.asc(ID));
        assertThat(sort.toList()).containsExactlyElementsOf(expected);
        assertThat(sort.toList().getLast().getProperty()).isEqualTo(ID);
    }

    @ParameterizedTest(name = "sortBy={0} ascending={1}")
    @CsvSource({
            "startTime,true", "startTime,false",
            "idAppointment,true", "idAppointment,false"
    })
    void listByShouldNotRepeatPropertiesWhenSortByIsAlreadyPartOfTheTieBreakers(String sortBy, boolean ascending) {
        repository.listBy(null, null, null, null, new PageQuery(0, 5, sortBy, ascending));

        List<Sort.Order> orders = capturedSort().toList();
        assertThat(orders).extracting(Sort.Order::getProperty).doesNotHaveDuplicates();
        // si sortBy ya es idAppointment, ese es el criterio principal y no se vuelve a añadir al final
        assertThat(orders).extracting(Sort.Order::getProperty).contains(ID);
        assertThat(orders.getFirst().getProperty()).isEqualTo(sortBy);
        assertThat(orders.getFirst().isAscending()).isEqualTo(ascending);
        if (!ID.equals(sortBy)) {
            assertThat(orders.getLast().getProperty()).isEqualTo(ID);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void listByShouldNeverRepeatPropertiesForAnyAllowedSortField(boolean ascending) {
        for (String sortBy : List.of("date", "appointmentState", "idDoctor", "idPatient", "startTime", ID)) {
            org.mockito.Mockito.clearInvocations(jpaRepository);
            repository.listBy(null, null, null, null, new PageQuery(0, 5, sortBy, ascending));

            assertThat(capturedSort().toList()).extracting(Sort.Order::getProperty)
                    .as("sortBy=%s", sortBy).doesNotHaveDuplicates().contains(ID);
        }
    }

    /**
     * El orden de la agenda vive dentro de la Specification ({@code query.orderBy}), no en el Pageable:
     * se ejecuta la Specification capturada contra un CriteriaBuilder simulado y se inspecciona el orderBy.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dailyAgendaShouldOrderByStatePriorityThenStartTimeThenUniqueId() {
        repository.ListDoctorDailyAgenda(UUID.randomUUID(), java.time.LocalDate.of(2041, 1, 1),
                new PageQuery(0, 5, "date", true));

        ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(jpaRepository).findAll(specCaptor.capture(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().isSorted()).isFalse();

        CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Root<AppointmentEntity> root = mock(Root.class);
        Path startTimePath = mock(Path.class);
        Path idPath = mock(Path.class);
        Order startTimeOrder = mock(Order.class);
        Order idOrder = mock(Order.class);
        lenient().when(root.get("startTime")).thenReturn(startTimePath);
        lenient().when(root.get(ID)).thenReturn(idPath);
        lenient().when(cb.asc(startTimePath)).thenReturn(startTimeOrder);
        lenient().when(cb.asc(idPath)).thenReturn(idOrder);

        specCaptor.getValue().toPredicate(root, query, cb);

        ArgumentCaptor<Order> orders = ArgumentCaptor.forClass(Order.class);
        verify(query).orderBy(orders.capture(), orders.capture(), orders.capture());
        assertThat(orders.getAllValues()).hasSize(3);
        assertThat(orders.getAllValues().get(1)).isSameAs(startTimeOrder);
        assertThat(orders.getAllValues().get(2)).isSameAs(idOrder);
    }
}
