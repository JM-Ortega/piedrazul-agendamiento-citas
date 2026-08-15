-- =====================================================================
-- V001__baseline_piedrazul_schema.sql
-- =====================================================================
-- Schema base del dominio de Piedrazul (sistema de gestión clínica).
-- Sigue el convenio documentado en convenio-migraciones-flyway.md.
--
-- Orden del archivo:
--   0. Extensiones y funciones de soporte
--   1. Catálogos (sin FK saliente)
--   2. Dominio principal (en orden de dependencia)
--   3. Relación N:M
--
-- No incluye: infraestructura de Spring Modulith (ver V002), datos
-- semilla de catálogos (ver R__seed_catalogs.sql), ni cifrado de
-- columnas (decisión de arquitectura pospuesta, no afecta esta
-- migración).
-- =====================================================================


-- =====================================================================
-- BLOQUE 0: EXTENSIONES Y FUNCIONES DE SOPORTE
-- =====================================================================
-- pg_trgm y unaccent son "trusted" desde PostgreSQL 13 — instalables por
-- migration_role sin ser superusuario (CREATE ya concedido sobre la base
-- y sobre el schema extensions, ver 01-init-databases.sh secciones 3-4).
--
-- unaccent(text) de un argumento viene marcada STABLE, no IMMUTABLE, así
-- que Postgres rechaza usarla dentro de una expresión de índice.
-- immutable_unaccent fija el diccionario explícitamente (forma de dos
-- argumentos), lo que la vuelve determinística y sí marcable IMMUTABLE.
-- =====================================================================

CREATE EXTENSION pg_trgm SCHEMA extensions;
CREATE EXTENSION unaccent SCHEMA extensions;

CREATE FUNCTION extensions.immutable_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
AS $$
    SELECT extensions.unaccent('extensions.unaccent', $1)
$$;


-- =====================================================================
-- BLOQUE 1: CATÁLOGOS
-- =====================================================================

-- ---------------------------------------------------------------------
-- Catálogo: specialty
-- Especialidades médicas que puede tener un doctor. Modelado como
-- catálogo porque la clínica planea ampliar su oferta de
-- especialidades con el tiempo.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.specialty (
    code   VARCHAR(40)  NOT NULL,
    name   VARCHAR(100) NOT NULL,

    CONSTRAINT pk_specialty PRIMARY KEY (code)
);

-- ---------------------------------------------------------------------
-- Catálogo: appointment_state
-- Estados posibles de una cita. Catálogo porque puede haber estados
-- nuevos en el tiempo.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.appointment_state (
    code   VARCHAR(40)  NOT NULL,
    name   VARCHAR(100) NOT NULL,

    CONSTRAINT pk_appointment_state PRIMARY KEY (code)
);

-- ---------------------------------------------------------------------
-- Catálogo: notification_type
-- Tipo de notificación enviada. Catálogo porque el dominio ya tiene
-- nombrados varios tipos futuros no implementados aún.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.notification_type (
    code   VARCHAR(60)  NOT NULL,
    name   VARCHAR(150) NOT NULL,

    CONSTRAINT pk_notification_type PRIMARY KEY (code)
);

-- ---------------------------------------------------------------------
-- Catálogo: verification_purpose
-- Propósito de un código de verificación (OTP).
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.verification_purpose (
    code   VARCHAR(40)  NOT NULL,
    name   VARCHAR(100) NOT NULL,

    CONSTRAINT pk_verification_purpose PRIMARY KEY (code)
);

/*
-- ---------------------------------------------------------------------
-- Catálogo: audit_action
-- Acción registrada en la bitácora de auditoría. Catálogo porque puede
-- crecer con el tiempo a medida que se instrumenten más acciones.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.audit_action (
    code   VARCHAR(60)  NOT NULL,
    name   VARCHAR(150) NOT NULL,

    CONSTRAINT pk_audit_action PRIMARY KEY (code)
);

-- ---------------------------------------------------------------------
-- Catálogo: audit_module
-- Módulo del sistema al que pertenece una acción auditada. Catálogo
-- porque puede crecer con nuevos módulos.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.audit_module (
    code   VARCHAR(40)  NOT NULL,
    name   VARCHAR(100) NOT NULL,

    CONSTRAINT pk_audit_module PRIMARY KEY (code)
);
 */

-- =====================================================================
-- Tabla: audit_module
-- Catálogo de módulos de negocio que pueden generar eventos de auditoría
-- (Citas, Usuarios, Historias Clínicas, Seguridad, etc.). Se usa para
-- agrupar audit_action por módulo, principalmente para que el frontend
-- pueda armar el filtro de auditoría organizado por sección del sistema.
-- =====================================================================
CREATE TABLE piedrazul.audit_module (
    code    VARCHAR(50)  NOT NULL,
    name    VARCHAR(100) NOT NULL,

    CONSTRAINT pk_audit_module PRIMARY KEY (code)
);

-- =====================================================================
-- Tabla: audit_action
-- Catálogo de acciones auditables del sistema. Modelado como catálogo
-- (no CHECK) porque la lista crece con cada nuevo caso de uso que se
-- audita — cada módulo de negocio (citas, usuarios, historias clínicas,
-- seguridad) puede aportar nuevas acciones a futuro.
-- =====================================================================
CREATE TABLE piedrazul.audit_action (
    code             VARCHAR(50)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    audit_module_code VARCHAR(50) NOT NULL,

    CONSTRAINT pk_audit_action PRIMARY KEY (code),
    CONSTRAINT fk_audit_action_audit_module FOREIGN KEY (audit_module_code)
    REFERENCES piedrazul.audit_module(code)
);

CREATE INDEX idx_audit_action_audit_module_code
    ON piedrazul.audit_action (audit_module_code);

-- =====================================================================
-- BLOQUE 2: DOMINIO PRINCIPAL
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tabla: person
-- Superclase de todo humano que interactúa con el sistema (paciente,
-- doctor, y potencialmente admin/staff sin fila propia). Roles de
-- autorización (admin/staff) viven exclusivamente en Keycloak, no se
-- modelan aquí.
--
-- user_id: identificador único de Keycloak. Sin FK real porque Keycloak
-- gestiona su propio schema/sistema, fuera del control de esta
-- migración. Nullable porque una persona puede existir en Piedrazul
-- antes de tener cuenta creada en Keycloak.
--
-- identification_type: catálogo de 4 valores fijo y estable en
-- Colombia, modelado con CHECK en vez de tabla catálogo (sin evidencia
-- de que vaya a crecer).
--
-- identification, phone, email: sin cifrado (decisión de arquitectura,
-- ver notas de la sesión de diseño). identification se mantiene en
-- texto plano a propósito para no romper el autocompletado por prefijo
-- ya existente en el sistema.
--
-- idx_person_full_name_trgm: cubre toda la tabla, no un subconjunto, porque
-- el predicado de un índice solo puede evaluar columnas de la propia fila,
-- nunca la pertenencia de esa fila a otra tabla.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.person (
    id                     UUID         NOT NULL,
    user_id                UUID,
    identification_type    VARCHAR(40)  NOT NULL,
    identification         VARCHAR(100) NOT NULL,
    first_name             VARCHAR(100) NOT NULL,
    last_name              VARCHAR(100) NOT NULL,
    phone                  VARCHAR(20)  NOT NULL,
    email                  VARCHAR(255),

    CONSTRAINT pk_person PRIMARY KEY (id),
    CONSTRAINT uq_person_user_id UNIQUE (user_id),
    CONSTRAINT uq_person_identification UNIQUE (identification),
    CONSTRAINT ck_person_identification_type CHECK (
        identification_type IN ('CEDULA', 'TARJETA_IDENTIDAD', 'REGISTRO_NACIMIENTO', 'PASAPORTE')
    )
);

CREATE INDEX idx_person_full_name_trgm
    ON piedrazul.person
    USING gin (extensions.immutable_unaccent(lower(first_name || ' ' || last_name)) extensions.gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Tabla: patient
-- Especialización de person
-- sex: sexo biológico (no identidad de género), por eso solo 2 valores.
-- guardian_phone: nullable en BD.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.patient (
    person_id        UUID        NOT NULL,
    sex              VARCHAR(20) NOT NULL,
    birth_date       DATE        NOT NULL,
    guardian_phone   VARCHAR(20),

    CONSTRAINT pk_patient PRIMARY KEY (person_id),
    CONSTRAINT fk_patient_person FOREIGN KEY (person_id) REFERENCES piedrazul.person(id),
    CONSTRAINT ck_patient_sex CHECK (sex IN ('MASCULINO', 'FEMENINO'))
);

-- ---------------------------------------------------------------------
-- Tabla: doctor
-- Especialización de person (mismo patrón shared primary key que
-- patient).
--
-- labor_end: NOT NULL — todo doctor tiene fecha de fin de vinculación
-- conocida desde el registro (confirmado con el negocio).
-- booking_window_weeks: cuántas semanas hacia adelante desde hoy se
-- permite agendar cita con este doctor (antes "time_window").
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.doctor (
    person_id               UUID    NOT NULL,
    labor_start             DATE    NOT NULL,
    labor_end               DATE    NOT NULL,
    booking_window_weeks    INTEGER NOT NULL,
    status                  BOOLEAN NOT NULL,
    appointment_interval    INTEGER NOT NULL,

    CONSTRAINT pk_doctor PRIMARY KEY (person_id),
    CONSTRAINT fk_doctor_person FOREIGN KEY (person_id) REFERENCES piedrazul.person(id),
    CONSTRAINT ck_doctor_booking_window_weeks CHECK (booking_window_weeks > 0)
);

-- ---------------------------------------------------------------------
-- Tabla: schedule
-- Bloque de horario semanal recurrente de un doctor. Horario continuo
--
-- workday: incluye SABADO aunque hoy no se use, para no requerir
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.schedule (
    id           UUID        NOT NULL,
    doctor_id    UUID        NOT NULL,
    start_time   TIME        NOT NULL,
    end_time     TIME        NOT NULL,
    workday      VARCHAR(20) NOT NULL,

    CONSTRAINT pk_schedule PRIMARY KEY (id),
    CONSTRAINT fk_schedule_doctor FOREIGN KEY (doctor_id) REFERENCES piedrazul.doctor(person_id),
    CONSTRAINT uq_schedule_doctor_workday UNIQUE (doctor_id, workday),
    CONSTRAINT ck_schedule_workday CHECK (
        workday IN ('LUNES', 'MARTES', 'MIERCOLES', 'JUEVES', 'VIERNES', 'SABADO')
    )
);

CREATE INDEX idx_schedule_doctor ON piedrazul.schedule (doctor_id);

-- ---------------------------------------------------------------------
-- Tabla: appointment
-- Cita médica. PK simple; patient_id y doctor_id son FK normales
--
-- specialty_code: con cuál especialidad del doctor fue agendada esta
-- cita puntual (un doctor puede tener varias especialidades).
-- state_code: transiciones válidas se manejan en el backend.
-- doctor.appointment_interval minutos a start_time.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.appointment (
    id                   UUID        NOT NULL,
    patient_id           UUID        NOT NULL,
    doctor_id            UUID        NOT NULL,
    specialty_code       VARCHAR(40) NOT NULL,
    state_code           VARCHAR(40) NOT NULL,
    date                 DATE        NOT NULL,
    start_time           TIME        NOT NULL,
    scheduling_origin    VARCHAR(20) NOT NULL,

    CONSTRAINT pk_appointment PRIMARY KEY (id),
    CONSTRAINT fk_appointment_patient FOREIGN KEY (patient_id) REFERENCES piedrazul.patient(person_id),
    CONSTRAINT fk_appointment_doctor FOREIGN KEY (doctor_id) REFERENCES piedrazul.doctor(person_id),
    CONSTRAINT fk_appointment_specialty FOREIGN KEY (specialty_code) REFERENCES piedrazul.specialty(code),
    CONSTRAINT fk_appointment_state FOREIGN KEY (state_code) REFERENCES piedrazul.appointment_state(code),
    CONSTRAINT ck_appointment_scheduling_origin CHECK (scheduling_origin IN ('MANUAL', 'AUTONOMO'))
);

CREATE INDEX idx_appointment_patient ON piedrazul.appointment (patient_id);
CREATE INDEX idx_appointment_doctor ON piedrazul.appointment (doctor_id);
CREATE INDEX idx_appointment_specialty ON piedrazul.appointment (specialty_code);
CREATE INDEX idx_appointment_state ON piedrazul.appointment (state_code);

-- ---------------------------------------------------------------------
-- Tabla: clinical_history
-- Registro de historia clínica asociado a una cita. patient_id se
-- mantiene explícito junto a appointment_id
-- doctor_name se mantiene como snapshot de texto por la misma razón.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.clinical_history (
    id                UUID         NOT NULL,
    patient_id        UUID         NOT NULL,
    appointment_id    UUID         NOT NULL,
    date_attention    DATE         NOT NULL,
    doctor_name       VARCHAR(200) NOT NULL,
    description       VARCHAR(500) NOT NULL,

    CONSTRAINT pk_clinical_history PRIMARY KEY (id),
    CONSTRAINT fk_clinical_history_patient FOREIGN KEY (patient_id) REFERENCES piedrazul.patient(person_id),
    CONSTRAINT fk_clinical_history_appointment FOREIGN KEY (appointment_id) REFERENCES piedrazul.appointment(id)
);

CREATE INDEX idx_clinical_history_patient ON piedrazul.clinical_history (patient_id);
CREATE INDEX idx_clinical_history_appointment ON piedrazul.clinical_history (appointment_id);

-- ---------------------------------------------------------------------
-- Tabla: notification
-- Notificación enviada a una persona (email, SMS, WhatsApp, consola).
--
-- aggregate_id/aggregate_type: patrón polimórfico, sin FK real, indica
-- a qué entidad de negocio pertenece esta notificación (una cita, una
-- verificación, un evento de sistema). Confirmado estable (3 valores,
-- sin evidencia de crecimiento) — se modela con CHECK.
-- type_code: catálogo (evidencia real de crecimiento en el código).
-- status: CHECK, máquina de estados validada en el dominio (backend).
-- recipient_id: FK real a person (antes patrón polimórfico separado por
-- tipo de destinatario; simplificado porque person ya cubre todos los
-- roles humanos).
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.notification (
    id                          UUID         NOT NULL,
    aggregate_id                UUID         NOT NULL,
    aggregate_type              VARCHAR(40)  NOT NULL,
    type_code                   VARCHAR(60)  NOT NULL,
    status                      VARCHAR(20)  NOT NULL,
    recipient_id                UUID         NOT NULL,
    recipient_name              VARCHAR(200) NOT NULL,
    recipient_phone              VARCHAR(20),
    recipient_email              VARCHAR(255),
    recipient_locale             VARCHAR(10),
    channel_preference_json      TEXT,
    variables_json               TEXT,
    idempotency_key              VARCHAR(255) NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT fk_notification_type FOREIGN KEY (type_code) REFERENCES piedrazul.notification_type(code),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES piedrazul.person(id),
    CONSTRAINT uq_notification_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_notification_aggregate_type CHECK (aggregate_type IN ('APPOINTMENT', 'VERIFICATION', 'SYSTEM')),
    CONSTRAINT ck_notification_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'DELIVERED', 'FAILED', 'CANCELLED', 'EXPIRED')
    )
);

CREATE INDEX idx_notification_type ON piedrazul.notification (type_code);
CREATE INDEX idx_notification_recipient ON piedrazul.notification (recipient_id);
CREATE INDEX idx_notification_aggregate ON piedrazul.notification (aggregate_id, aggregate_type);

-- ---------------------------------------------------------------------
-- Tabla: notification_schedule
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.notification_schedule (
    id                        UUID        NOT NULL,
    notification_id           UUID        NOT NULL,
    status                    VARCHAR(20) NOT NULL,
    scheduled_at              TIMESTAMPTZ NOT NULL,
    cancelled_at              TIMESTAMPTZ,
    next_retry_at             TIMESTAMPTZ,
    processing_started_at     TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_notification_schedule PRIMARY KEY (id),
    CONSTRAINT fk_notification_schedule_notification FOREIGN KEY (notification_id) REFERENCES piedrazul.notification(id),
    CONSTRAINT uq_notification_schedule_notification UNIQUE (notification_id),
    CONSTRAINT ck_notification_schedule_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED', 'EXPIRED')
    )
);

-- ---------------------------------------------------------------------
-- Tabla: notification_attempts
-- Intento individual de envío de una notificación por un canal
-- específico. notification_id y schedule_id se mantienen ambas como FK
-- (no redundancia inútil): notification_id es la FK funcionalmente
-- activa (usada en conteos/índices reales del dispatcher), schedule_id
-- es trazabilidad de qué disparo concreto generó el intento.
--
-- UNIQUE(notification_id, channel, attempt_number): cierra una
-- condición de carrera real detectada en el código (patrón
-- "count-then-insert" sin protección transaccional en
-- nextAttemptNumber()). Ver notas de backend: hay que capturar
-- DataIntegrityViolationException al insertar.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.notification_attempts (
    id                      UUID         NOT NULL,
    notification_id         UUID         NOT NULL,
    schedule_id              UUID         NOT NULL,
    channel                  VARCHAR(20)  NOT NULL,
    attempt_number            INTEGER      NOT NULL,
    status                    VARCHAR(20)  NOT NULL,
    provider_name              VARCHAR(100),
    provider_message_id        VARCHAR(255),
    failure_type                VARCHAR(20),
    error_code                   VARCHAR(100),
    error_message                  VARCHAR(500),
    accepted_at                    TIMESTAMPTZ,
    sent_at                         TIMESTAMPTZ,
    delivered_at                     TIMESTAMPTZ,
    failed_at                         TIMESTAMPTZ,
    created_at                        TIMESTAMPTZ NOT NULL,
    updated_at                        TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_notification_attempts PRIMARY KEY (id),
    CONSTRAINT fk_notification_attempts_notification FOREIGN KEY (notification_id) REFERENCES piedrazul.notification(id),
    CONSTRAINT fk_notification_attempts_schedule FOREIGN KEY (schedule_id) REFERENCES piedrazul.notification_schedule(id),
    CONSTRAINT uq_notification_attempts_number UNIQUE (notification_id, channel, attempt_number),
    CONSTRAINT ck_notification_attempts_channel CHECK (channel IN ('CONSOLE', 'EMAIL', 'SMS', 'WHATSAPP')),
    CONSTRAINT ck_notification_attempts_failure_type CHECK (
        failure_type IS NULL OR failure_type IN ('TEMPORARY', 'PERMANENT', 'CIRCUIT_OPEN', 'UNKNOWN')
    ),
    CONSTRAINT ck_notification_attempts_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'BOUNCED', 'UNDELIVERED', 'CANCELLED', 'UNKNOWN')
    )
);

CREATE INDEX idx_notification_attempts_notification_channel ON piedrazul.notification_attempts (notification_id, channel);
CREATE INDEX idx_notification_attempts_schedule ON piedrazul.notification_attempts (schedule_id);

-- ---------------------------------------------------------------------
-- Tabla: notification_delivery_events
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.notification_delivery_events (
    id                      UUID         NOT NULL,
    attempt_id              UUID         NOT NULL,
    event_timestamp         TIMESTAMPTZ  NOT NULL,
    event_type              VARCHAR(100) NOT NULL,
    normalized_status       VARCHAR(20)  NOT NULL,
    raw_status              VARCHAR(100) NOT NULL,
    payload_json            TEXT,
    provider_name           VARCHAR(100) NOT NULL,
    provider_message_id     VARCHAR(255) NOT NULL,
    provider_event_id       VARCHAR(255),
    received_at             TIMESTAMPTZ  NOT NULL,
    processed_at            TIMESTAMPTZ,

    CONSTRAINT pk_notification_delivery_events PRIMARY KEY (id),
    CONSTRAINT fk_notification_delivery_events_attempt FOREIGN KEY (attempt_id) REFERENCES piedrazul.notification_attempts(id),
    CONSTRAINT ck_notification_delivery_events_normalized_status CHECK (
        normalized_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'BOUNCED', 'UNDELIVERED', 'CANCELLED', 'UNKNOWN')
    )
);

CREATE INDEX idx_delivery_event_attempt ON piedrazul.notification_delivery_events (attempt_id);
CREATE INDEX idx_delivery_event_provider_message ON piedrazul.notification_delivery_events (provider_name, provider_message_id);

CREATE UNIQUE INDEX uq_delivery_event_provider_event
    ON piedrazul.notification_delivery_events (provider_name, provider_event_id)
    WHERE provider_event_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Tabla: verification_code
-- Código de verificación (OTP) para flujos como vinculación de cuenta.
--
-- subject: identificador de negocio en texto libre (hoy: número de
-- documento), SIN FK hacia person. Decisión consciente: el módulo
-- verification está diseñado para permanecer desacoplado de otros
-- módulos (Spring Modulith), la dirección de dependencia real es
-- patients -> verification, nunca al revés. Forzar una FK rompería esa
-- frontera modular a propósito.
-- code_hash: BCrypt (confirmado en código) — no determinístico, no se
-- puede indexar ni buscar por este campo.
-- max_attempts: snapshot por fila (hoy siempre 5, hardcodeado en el
-- backend, pero el campo permite variar por fila a futuro sin migrar).
--
-- Índice único parcial: cierra una condición de carrera real detectada
-- en el código (patrón "leer-invalidar-crear" sin lock explícito en
-- requestCode()). Parcial porque solo debe existir un código activo
-- (used = false) a la vez por subject+purpose; códigos ya usados
-- pueden coexistir sin problema.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.verification_code (
    id              UUID         NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    purpose_code    VARCHAR(40)  NOT NULL,
    code_hash       VARCHAR(255) NOT NULL,
    attempts        INTEGER      NOT NULL,
    max_attempts    INTEGER      NOT NULL,
    used            BOOLEAN      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_verification_code PRIMARY KEY (id),
    CONSTRAINT fk_verification_code_purpose FOREIGN KEY (purpose_code) REFERENCES piedrazul.verification_purpose(code)
);

CREATE INDEX idx_verification_code_purpose ON piedrazul.verification_code (purpose_code);

CREATE UNIQUE INDEX uq_verification_code_active
    ON piedrazul.verification_code (subject, purpose_code)
    WHERE used = false;

-- =====================================================================
-- Tabla: audit_event
-- Registro de auditoría append-only del sistema.
-- =====================================================================
CREATE TABLE piedrazul.audit_event (
    id                  UUID                     NOT NULL,
    occurred_at         TIMESTAMPTZ               NOT NULL,
    actor_username      VARCHAR(100)              NOT NULL,
    actor_role          VARCHAR(50),
    action_code         VARCHAR(50)               NOT NULL,
    target_entity_type  VARCHAR(100),
    target_entity_id    VARCHAR(100),
    outcome             VARCHAR(20)               NOT NULL,
    correlation_id      VARCHAR(100),
    before_state        TEXT,
    after_state         TEXT,

    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_audit_action FOREIGN KEY (action_code)
        REFERENCES piedrazul.audit_action(code),
    CONSTRAINT ck_audit_event_outcome CHECK (
    outcome IN ('EXITOSO', 'FALLIDO', 'DENEGADO')
    )
);

CREATE INDEX idx_audit_event_action_code
    ON piedrazul.audit_event (action_code);

CREATE INDEX idx_audit_event_actor_username
    ON piedrazul.audit_event (actor_username, occurred_at DESC);

CREATE INDEX idx_audit_event_target
    ON piedrazul.audit_event (target_entity_type, target_entity_id);

CREATE INDEX idx_audit_event_occurred_at
    ON piedrazul.audit_event (occurred_at DESC);

CREATE INDEX idx_audit_event_correlation_id
    ON piedrazul.audit_event (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- =====================================================================
-- Refuerzo de integridad sobre piedrazul.audit_event: prohíbe UPDATE y
-- DELETE tanto a nivel de permisos de rol como con un trigger de
-- defensa en profundidad.
-- =====================================================================
REVOKE UPDATE, DELETE ON piedrazul.audit_event FROM piedrazul_app;
GRANT SELECT, INSERT ON piedrazul.audit_event TO piedrazul_app;

CREATE FUNCTION piedrazul.prevent_audit_event_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event es append-only: % no está permitido', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_event_no_update
    BEFORE UPDATE ON piedrazul.audit_event
    FOR EACH ROW EXECUTE FUNCTION piedrazul.prevent_audit_event_mutation();

CREATE TRIGGER trg_audit_event_no_delete
    BEFORE DELETE ON piedrazul.audit_event
    FOR EACH ROW EXECUTE FUNCTION piedrazul.prevent_audit_event_mutation();

-- =====================================================================
-- BLOQUE 3: RELACIÓN N:M
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tabla: doctor_specialty
-- Relación N:M entre doctor y specialty: un doctor puede tener varias
-- especialidades, una especialidad puede estar en varios doctores.
-- ---------------------------------------------------------------------
CREATE TABLE piedrazul.doctor_specialty (
    doctor_id         UUID        NOT NULL,
    specialty_code    VARCHAR(40) NOT NULL,

    CONSTRAINT pk_doctor_specialty PRIMARY KEY (doctor_id, specialty_code),
    CONSTRAINT fk_doctor_specialty_doctor FOREIGN KEY (doctor_id) REFERENCES piedrazul.doctor(person_id),
    CONSTRAINT fk_doctor_specialty_specialty FOREIGN KEY (specialty_code) REFERENCES piedrazul.specialty(code)
);

CREATE INDEX idx_doctor_specialty_specialty ON piedrazul.doctor_specialty (specialty_code);