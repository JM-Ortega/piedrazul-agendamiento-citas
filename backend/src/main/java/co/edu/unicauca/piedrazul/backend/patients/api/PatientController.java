package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.CreatePatientWithUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.ConfirmLinkUserAccountRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.RequestLinkUserAccountCodeRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.UpdateOwnPatientRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.UpdatePatientRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientSummaryResponse;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientUpdateService;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import co.edu.unicauca.piedrazul.backend.shared.audit.AuditTargetType;
import co.edu.unicauca.piedrazul.backend.shared.audit.Auditable;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

@Tag(name = "Pacientes", description = "Registro, consulta y actualización de pacientes, y habilitación de su cuenta de acceso")
@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;
    private final PatientUpdateService patientUpdateService;
    private final AppointmentExternalService appointmentExternalService;
    private final SecurityContextExtractor securityContextExtractor;
    private final PersonExternalService personExternalService;

    public PatientController(PatientService patientService, PatientUpdateService patientUpdateService,
                             AppointmentExternalService appointmentExternalService,
                             SecurityContextExtractor securityContextExtractor, PersonExternalService personExternalService) {
        this.patientService = patientService;
        this.patientUpdateService = patientUpdateService;
        this.appointmentExternalService = appointmentExternalService;
        this.securityContextExtractor = securityContextExtractor;
        this.personExternalService = personExternalService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registrar un paciente",
            description = "Crea un paciente sin cuenta de acceso. Lo hace el administrador. El documento no puede estar registrado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente registrado correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos o faltantes (documento, nombre, sexo, fecha de nacimiento, teléfono del acudiente para menores)"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para registrar pacientes"),
            @ApiResponse(responseCode = "409", description = "Ya existe una persona con ese documento")
    })
    public PatientResponse create(@Valid @RequestBody CreatePatientRequest request) {
        PatientData patient = patientService.createPatient(
                request.getIdentificationType(),
                request.getIdentification(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail(),
                null,
                request.getSex(),
                request.getBirthDate(),
                request.getGuardianPhone()
        );
        return toResponse(patient);
    }

    @PostMapping("/with-user")
    @Operation(summary = "Registrarse como paciente con cuenta",
            description = "Endpoint público de autorregistro: crea el paciente junto con su cuenta de acceso. El nombre de usuario debe ser igual al número de documento y la contraseña debe cumplir los requisitos de seguridad. Si el documento o el usuario ya existen, se rechaza.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente y cuenta creados correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos: usuario distinto al documento, contraseña inválida, sexo o fecha de nacimiento faltantes, o teléfono del acudiente faltante en menores"),
            @ApiResponse(responseCode = "409", description = "Ya existe una persona o una cuenta con ese documento")
    })
    public PatientResponse createWithUser(@Valid @RequestBody CreatePatientWithUserRequest request) {
        PatientData patient = patientService.createPatientWithUser(
                request.getUsername(),
                request.getPassword(),
                request.getIdentificationType(),
                request.getIdentification(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail(),
                request.getSex(),
                request.getBirthDate(),
                request.getGuardianPhone()
        );
        return toResponse(patient);
    }

    @PostMapping("/link-user-account/request-code")
    @Operation(summary = "Solicitar el código para habilitar el acceso",
            description = "Endpoint público. Envía al paciente un código de verificación para habilitar o vincular su cuenta de acceso a un documento ya registrado. El código se usa en la confirmación.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Código enviado correctamente"),
            @ApiResponse(responseCode = "400", description = "Documento inválido o en un estado inconsistente"),
            @ApiResponse(responseCode = "404", description = "No existe una persona con ese documento"),
            @ApiResponse(responseCode = "409", description = "El acceso de ese paciente ya está habilitado"),
            @ApiResponse(responseCode = "429", description = "Se bloquearon temporalmente las solicitudes de código para ese documento")
    })
    public void requestLinkUserAccountCode(@Valid @RequestBody RequestLinkUserAccountCodeRequest request) {
        patientService.requestLinkUserAccountCode(request.getIdentification());
    }

    @PostMapping("/link-user-account/confirm")
    @Operation(summary = "Confirmar el código y habilitar el acceso",
            description = "Endpoint público. Verifica el código y habilita el acceso del paciente, completando lo que falte: la contraseña si aún no tiene cuenta; el sexo y la fecha de nacimiento si aún no existe como paciente, y el teléfono del acudiente si es menor de edad.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Acceso habilitado correctamente"),
            @ApiResponse(responseCode = "400", description = "Código inválido o vencido, o faltan datos requeridos"),
            @ApiResponse(responseCode = "404", description = "No existe una persona con ese documento"),
            @ApiResponse(responseCode = "409", description = "El acceso de ese paciente ya está habilitado"),
            @ApiResponse(responseCode = "429", description = "Demasiados intentos fallidos con el código")
    })
    public PatientResponse confirmLinkUserAccount(@Valid @RequestBody ConfirmLinkUserAccountRequest request) {
        PatientData patient = patientService.confirmLinkUserAccount(
                request.getIdentification(),
                request.getCode(),
                request.getPassword(),
                request.getSex(),
                request.getBirthDate(),
                request.getGuardianPhone()
        );
        return toResponse(patient);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Consultar mi perfil de paciente",
            description = "Devuelve los datos del paciente autenticado, identificado por su cuenta.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Perfil obtenido correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar su perfil"),
            @ApiResponse(responseCode = "404", description = "La cuenta no tiene un paciente asociado")
    })
    public PatientResponse findMe(@AuthenticationPrincipal Jwt jwt) {
        UUID keycloakId = UUID.fromString(jwt.getSubject());
        PatientData patient = patientService.findByUserId(keycloakId)
                .orElseThrow(() -> new PatientNotFoundException(keycloakId));
        return toResponse(patient);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
    @Operation(summary = "Consultar un paciente por id",
            description = "Devuelve los datos completos de un paciente. Para agendadores y doctores.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente obtenido correctamente"),
            @ApiResponse(responseCode = "400", description = "El identificador no es válido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar pacientes"),
            @ApiResponse(responseCode = "404", description = "No existe un paciente con ese identificador")
    })
    public PatientResponse findById(@Parameter(description = "Identificador único (UUID) del paciente") @PathVariable UUID id) {
        PatientData patient = patientService.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));
        return toResponse(patient);
    }

    @GetMapping("/document/{documentNumber}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "Consultar un paciente por documento",
            description = "Devuelve los datos completos del paciente con el número de documento indicado. Para agendadores, doctores y administradores.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente obtenido correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar pacientes"),
            @ApiResponse(responseCode = "404", description = "No existe un paciente con ese documento")
    })
    public PatientResponse findByDocument(@Parameter(description = "Número de documento del paciente") @PathVariable String documentNumber) {
        PatientData patient = patientService.findByDocumentNumber(documentNumber)
                .orElseThrow(() -> new PatientNotFoundException(documentNumber));
        return toResponse(patient);
    }

    @GetMapping("/search/by-document-prefix")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
    @Operation(summary = "Buscar pacientes por inicio de documento",
            description = "Devuelve un resumen (id, documento y nombre) de los pacientes cuyo número de documento empieza por el prefijo indicado. Útil para autocompletar.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Búsqueda realizada correctamente; la lista puede estar vacía"),
            @ApiResponse(responseCode = "400", description = "El prefijo está vacío"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para buscar pacientes")
    })
    public List<PatientSummaryResponse> searchByDocumentPrefix(@Parameter(description = "Primeros dígitos del documento a buscar") @RequestParam String documentPrefix) {
        return patientService.searchByDocumentNumberPrefix(documentPrefix)
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    /**
     * Página de pacientes ordenada por nombre. {@code search} filtra por nombre
     * completo o número de documento; el orden no es configurable.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR', 'ADMIN')")
    @Operation(summary = "Listar pacientes con paginación",
            description = "Devuelve una página de pacientes ordenada por nombre (el orden no es configurable). El filtro search busca por nombre completo o número de documento. Parámetros de paginación: page (desde 0) y size (10 por defecto).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de pacientes obtenida correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para listar pacientes")
    })
    public PageResponse<PatientSummaryResponse> findAll(
            @PageableDefault(size = 10) Pageable pageable,
            @Parameter(description = "Texto para filtrar por nombre completo o número de documento")
            @RequestParam(required = false) String search
    ) {
        return PageResponse.from(patientService.search(search, pageable));
    }

    /** Reemplaza todos los datos del paciente, incluido el documento. Solo doctores. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Actualizar un paciente",
            description = "Reemplaza todos los datos del paciente, incluido el documento. Solo doctores. El nuevo documento no puede pertenecer a otra persona. El cambio queda registrado en la auditoría.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente actualizado correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos o faltantes"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar pacientes"),
            @ApiResponse(responseCode = "404", description = "No existe un paciente con ese identificador"),
            @ApiResponse(responseCode = "409", description = "El documento ya pertenece a otra persona")
    })
    @Auditable(action = AuditAction.PACIENTE_MODIFICADO, targetEntityType = AuditTargetType.PACIENTE, targetIdExpression = "#id", onlyDenied = true)
    public PatientResponse update(@Parameter(description = "Identificador único (UUID) del paciente") @PathVariable UUID id, @Valid @RequestBody UpdatePatientRequest request) {
        return toResponse(patientUpdateService.updatePatient(id, request.toCommand()));
    }

    /** El paciente reemplaza sus propios datos. No puede cambiar su documento. */
    @PutMapping("/me")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Actualizar mis datos de paciente",
            description = "El paciente autenticado reemplaza sus propios datos. No puede cambiar su documento. El cambio queda registrado en la auditoría.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Datos actualizados correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos inválidos o faltantes"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar su perfil"),
            @ApiResponse(responseCode = "404", description = "La cuenta no tiene un paciente asociado")
    })
    @Auditable(action = AuditAction.PACIENTE_MODIFICADO, targetEntityType = AuditTargetType.PACIENTE, onlyDenied = true)
    public PatientResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateOwnPatientRequest request) {
        UUID keycloakId = UUID.fromString(jwt.getSubject());
        return toResponse(patientUpdateService.updateOwnPatient(keycloakId, request.toCommand()));
    }

    @GetMapping("/{id}/exists")
    @PreAuthorize("hasRole('SCHEDULER')")
    @Operation(summary = "Verificar si existe un paciente",
            description = "Devuelve true si existe un paciente con el identificador indicado y false en caso contrario. Para agendadores.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verificación realizada; el cuerpo es true o false"),
            @ApiResponse(responseCode = "400", description = "El identificador no es válido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para verificar pacientes")
    })
    public boolean existsById(@Parameter(description = "Identificador único (UUID) del paciente") @PathVariable UUID id) {
        return patientService.existsById(id);
    }

    @GetMapping("/document/{documentNumber}/public")
    @Operation(summary = "Consultar el estado público de un documento",
            description = "Endpoint público. Indica, sin exponer datos sensibles, si el documento corresponde a un paciente, si tiene cuenta de acceso vinculada y si esa cuenta tiene el rol de paciente. El documento se devuelve enmascarado. Sirve para decidir si el usuario debe registrarse o habilitar su acceso.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado del documento obtenido correctamente"),
            @ApiResponse(responseCode = "404", description = "El documento no tiene persona ni cuenta asociada")
    })
    public PatientPublicResponse findPublicByDocument(@Parameter(description = "Número de documento a consultar") @PathVariable String documentNumber) {
        return patientService.findPublicByDocumentNumber(documentNumber);
    }

    @GetMapping("/{appointmentId}/patient-attended")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Consultar el paciente de una cita",
            description = "Devuelve los datos del paciente al que corresponde la cita indicada. Para doctores.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paciente obtenido correctamente"),
            @ApiResponse(responseCode = "400", description = "El identificador de la cita no es válido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar el paciente de una cita"),
            @ApiResponse(responseCode = "404", description = "No existe la cita o su paciente")
    })
    public PatientResponse getPatientByAppointment(@Parameter(description = "Identificador único (UUID) de la cita") @PathVariable UUID appointmentId) {
        UUID patientId = appointmentExternalService.getPattientIdByAppointmentId(appointmentId);
        PatientData patient = patientService.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        return toResponse(patient);
    }

    @GetMapping("/document-types")
    @Operation(summary = "Listar los tipos de documento",
            description = "Endpoint público. Devuelve los tipos de documento de identificación admitidos para registrar un paciente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tipos de documento obtenidos correctamente")
    })
    public List<IdentificationType> findAllDocumentTypes() {
        return patientService.getAllDocumentTypes();
    }

    @GetMapping("/gender-types")
    @Operation(summary = "Listar los tipos de género",
            description = "Endpoint público. Devuelve los géneros admitidos para registrar o actualizar un paciente: el valor que se envía en el campo `sex`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tipos de género obtenidos correctamente")
    })
    public List<PatientSex> findAllGenderTypes() {
        return patientService.getAllGenderTypes();
    }

    private PatientResponse toResponse(PatientData patient) {
        return new PatientResponse(
                patient.personId(),
                patient.userId(),
                patient.identificationType(),
                patient.identification(),
                patient.firstName(),
                patient.lastName(),
                patient.phone(),
                patient.email(),
                patient.sex(),
                patient.birthDate(),
                patient.guardianPhone()
        );
    }

    private PatientSummaryResponse toSummaryResponse(PatientData patient) {
        return new PatientSummaryResponse(
                patient.personId(),
                patient.identification(),
                patient.firstName(),
                patient.lastName()
        );
    }
}