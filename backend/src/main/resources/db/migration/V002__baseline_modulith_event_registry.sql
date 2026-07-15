-- =====================================================================
--V002__baseline_modulith_event_registry.sql
-- =====================================================================
-- Registro de publicación de eventos de Spring Modulith (schema JDBC v2).
-- Requiere: completion-mode: ARCHIVE en application.yml
-- Fuente: spring-modulith-events-jdbc-2.0.1.jar
--         org/springframework/modulith/events/jdbc/schemas/v2/
-- =====================================================================


-- =====================================================================
-- Tabla: event_publication
-- Eventos de dominio pendientes o en proceso de ser consumidos por
-- los listeners registrados en la aplicación (Spring Modulith events).
-- =====================================================================
CREATE TABLE piedrazul.event_publication
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON piedrazul.event_publication USING hash (serialized_event);

CREATE INDEX event_publication_by_completion_date_idx
    ON piedrazul.event_publication (completion_date);


-- =====================================================================
-- Tabla: event_publication_archive
-- Eventos ya completados exitosamente, movidos aquí cuando
-- completion-mode: ARCHIVE está activo en application.yml.
-- =====================================================================
CREATE TABLE piedrazul.event_publication_archive
(
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,

    CONSTRAINT pk_event_publication_archive PRIMARY KEY (id)
);

CREATE INDEX event_publication_archive_serialized_event_hash_idx
    ON piedrazul.event_publication_archive USING hash (serialized_event);

CREATE INDEX event_publication_archive_by_completion_date_idx
    ON piedrazul.event_publication_archive (completion_date);