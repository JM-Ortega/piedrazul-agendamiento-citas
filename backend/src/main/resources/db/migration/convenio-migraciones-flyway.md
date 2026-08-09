# Convenio de migraciones SQL — Flyway / Piedrazul

Guía de referencia para escribir archivos `V00X__descripcion.sql` de forma consistente.
Basada en documentación oficial de PostgreSQL y guías de estilo usadas en la industria
(ver sección de fuentes al final).

---

## 1. Principio general

Cada archivo de migración se organiza en **bloques por tabla**. Cada bloque es
autocontenido: contiene todo lo que pertenece a esa tabla (comentario, columnas,
constraints, índices) antes de pasar a la siguiente tabla. No se separan índices
o constraints en secciones aparte al final del archivo.

Motivo: un índice o constraint siempre pertenece a una tabla específica. Mantenerlo
pegado a su tabla permite leer, modificar o eliminar esa tabla como una unidad completa,
sin tener que saltar a otras partes del archivo para saber qué le pertenece.

---

## 2. Orden de las tablas dentro del archivo

Las tablas se escriben en **orden de dependencia**, para que Postgres pueda crear cada
objeto sin errores de referencia:

1. **Tablas catálogo / referencia** — no tienen FK saliente (no dependen de nada).
   Ejemplo conceptual: tipos, estados, roles, listas fijas.
2. **Tablas principales del dominio** — dependen de catálogos y/o de otras tablas de
   dominio ya creadas antes.
3. **Tablas de relación N:M** — tienen múltiples FK, van al final porque necesitan que
   ambos lados ya existan.

Si una migración solo contiene infraestructura de una librería externa (ver sección 6),
este orden de dependencia sigue aplicando dentro de ese archivo, pero el archivo en sí
se mantiene separado del dominio de negocio.

---

## 3. Estructura de un bloque de tabla

```sql
-- =====================================================================
-- Tabla: <nombre>
-- Qué es, para qué existe, decisiones de diseño relevantes.
-- =====================================================================
CREATE TABLE <schema>.<tabla> (
    columna_1   TIPO NOT NULL,
    columna_2   TIPO,
    ...

    CONSTRAINT pk_<tabla> PRIMARY KEY (columna_1),
    CONSTRAINT fk_<tabla>_<tabla_ref> FOREIGN KEY (columna_x) REFERENCES <schema>.<tabla_ref>(id),
    CONSTRAINT uq_<tabla>_<columna> UNIQUE (columna_y),
    CONSTRAINT ck_<tabla>_<columna> CHECK (columna_z IN (...))
);

CREATE INDEX idx_<tabla>_<columna> ON <schema>.<tabla> (columna_referenciada_por_fk);
```

La documentación (`COMMENT ON TABLE`) va **encima de cada tabla**, no en un bloque
separado al final del archivo. Así toda la tabla se lee de una sola pasada: qué es,
por qué existe, y el código que la implementa.

---

## 4. Qué va inline (dentro del `CREATE TABLE`) y qué no

| Elemento                                 | ¿Dónde va?                                              | Por qué                                                                             |
| ---------------------------------------- | ------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `PRIMARY KEY`                            | Inline, dentro del `CREATE TABLE`                       | Define la identidad de la fila                                                      |
| `FOREIGN KEY`                            | Inline                                                  | Define la relación, la tabla queda autodocumentada                                  |
| `NOT NULL`                               | Inline                                                  | Es parte intrínseca de la columna                                                   |
| `CHECK`                                  | Inline                                                  | Regla de esa columna/tabla específica                                               |
| `UNIQUE` (1 columna o varias combinadas) | Inline, como constraint de tabla                        | Es integridad de datos, igual que PK/FK                                             |
| `CREATE INDEX` (rendimiento)             | Pegado justo debajo del `CREATE TABLE` al que pertenece | No es integridad, es optimización de lectura — pero sigue perteneciendo a esa tabla |

**Nota importante sobre FK e índices:** declarar un `FOREIGN KEY` en Postgres **no crea
automáticamente un índice** sobre la columna que referencia. Solo `PRIMARY KEY` y
`UNIQUE` crean índice automático. Por eso, si una columna FK se usa en búsquedas o joins
frecuentes, su índice se agrega explícitamente con `CREATE INDEX` justo debajo de la
tabla — no se asume que ya existe por tener la FK.

---

## 5. Convención de nombres

| Tipo de objeto | Patrón                            | Ejemplo                        |
| -------------- | --------------------------------- | ------------------------------ |
| Primary Key    | `pk_<tabla>`                      | `pk_paciente`                  |
| Foreign Key    | `fk_<tabla>_<tabla_referenciada>` | `fk_cita_paciente`             |
| Unique         | `uq_<tabla>_<columna(s)>`         | `uq_paciente_numero_documento` |
| Check          | `ck_<tabla>_<columna>`            | `ck_paciente_edad`             |
| Índice normal  | `idx_<tabla>_<columna>`           | `idx_cita_fecha`               |

Siempre se nombra el constraint explícitamente (`CONSTRAINT nombre ...`), nunca se deja
que Postgres le asigne un nombre autogenerado. Esto hace que cualquier `ALTER TABLE ...
DROP CONSTRAINT` futuro sea predecible, y evita ambigüedad al inspeccionar el esquema.

---

## 6. Tablas que no son del dominio propio (librerías externas)

Si una tabla viene definida por una librería externa (ejemplo: registro de eventos de
Spring Modulith), **no se mezcla en el mismo archivo que las tablas de dominio**, aunque
se sigan las mismas reglas de estilo (schema explícito, constraints nombrados, índices
pegados a su tabla).

Razón: son piezas de infraestructura con ciclo de vida propio, definidas por el jar/
librería y no por decisiones de negocio del equipo. Mantenerlas en su propio archivo
(`V00X__baseline_<nombre_libreria>.sql`) permite actualizarlas de forma aislada si la
librería cambia de versión, sin tocar ni arriesgar las migraciones de dominio.

En estas migraciones, si el snippet oficial de la librería trae `IF NOT EXISTS`, se
retira: Flyway garantiza que cada migración versionada corre una sola vez (queda
registrada en `flyway_schema_history`), así que si algo falla a mitad de camino debe
quedar marcado como fallido y resolverse explícitamente, no "esconderse" con
`IF NOT EXISTS`.

---

## 7. Datos semilla de catálogos: repeatable migrations (`R__`)

Los catálogos (ver sección 9 para el criterio de cuándo una columna se modela como
catálogo) son tablas de **solo lectura para la aplicación**: Spring Boot las consulta
por FK, pero nunca modifica sus filas en tiempo de ejecución. Sus datos semilla se
manejan con **repeatable migrations** (`R__seed_<algo>.sql`), no con migraciones
versionadas (`V__`).

Motivo: si se necesita corregir o ampliar un valor semilla (ej. renombrar una
especialidad, agregar una nueva), basta con editar el archivo `R__` — Flyway lo
reaplica automáticamente al detectar el cambio de checksum. Con `V__`, cada ajuste
cosmético a un catálogo acumularía una nueva migración versionada solo para eso,
ensuciando el historial con migraciones que no representan cambios reales de
estructura.

Regla estricta para que esto sea seguro: el `INSERT` siempre debe ser **idempotente**,
usando `ON CONFLICT (code) DO NOTHING`. Nunca un `INSERT` plano (fallaría al
reaplicarse el archivo, ya que Flyway reejecuta el script completo cada vez que
cambia su checksum). Esa idempotencia es el único requisito no negociable — la
granularidad del archivo no lo es: este proyecto agrupa todos los catálogos
relacionados en un único `R__seed_catalogs.sql` en vez de un archivo por catálogo,
porque son pocos (media docena) y cambian con poca frecuencia. Editar un catálogo
reaplica el archivo completo, lo cual es inofensivo gracias al `ON CONFLICT DO
NOTHING` de cada bloque. Si en el futuro los catálogos crecen en número o en
frecuencia de cambio independiente entre sí, separarlos en archivos `R__seed_<catalogo>.sql`
individuales es una alternativa igual de válida — es preferencia de organización,
no una regla de Flyway.

```sql
-- R__seed_catalogs.sql
-- ---------------------------------------------------------------------
-- specialty
-- ---------------------------------------------------------------------
INSERT INTO piedrazul.specialty (code, name) VALUES
    ('FISIOTERAPIA', 'Fisioterapia'),
    ('TERAPIA_NEURAL', 'Terapia Neural'),
    ('QUIROPRAXIA', 'Quiropraxia'),
    ('MEDICINA_GENERAL', 'Medicina General')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- appointment_state
-- ---------------------------------------------------------------------
INSERT INTO piedrazul.appointment_state (code, name) VALUES
    ('AGENDADA', 'Agendada'),
    ('CANCELADA', 'Cancelada')
ON CONFLICT (code) DO NOTHING;
```

**Excepción — cuándo NO usar `R__`:** cualquier dato semilla que la aplicación vaya a
modificar después en tiempo de ejecución (ej. un usuario administrador inicial cuya
contraseña se actualiza desde la UI, o cualquier fila que un servicio de dominio
pueda llegar a `UPDATE`) va en una migración **versionada** (`V__`), nunca en
repeatable. Si Flyway reaplicara un `R__` sobre una fila que la aplicación ya
modificó, pisaría silenciosamente ese cambio la próxima vez que el checksum del
archivo cambie por cualquier otro motivo.

---

## 8. Schema explícito vs. `search_path`

Aunque los roles de conexión (`migration_role`, `app_role`) ya tengan un `search_path`
configurado a nivel de rol (vía `ALTER ROLE ... SET search_path = ...` en el bootstrap),
**se sigue escribiendo el schema de forma explícita** en cada `CREATE TABLE` / `CREATE
INDEX` (ej. `piedrazul.paciente`, no solo `paciente`).

Motivo: el archivo de migración queda autosuficiente y legible sin depender de conocer
la configuración externa del rol. No se agrega `SET search_path` dentro del archivo de
migración — sería una segunda fuente de verdad para algo que ya está resuelto a nivel
de rol, con riesgo de quedar desincronizado si algún día cambia uno de los dos lugares.

---

## 9. Criterio de decisión: CHECK constraint vs. tabla catálogo

Cuando una columna solo admite un conjunto cerrado de valores (un "enum" de negocio),
hay que decidir entre dos formas de garantizarlo:

|                                                        | CHECK constraint                                                            | Tabla catálogo                                                  |
| ------------------------------------------------------ | --------------------------------------------------------------------------- | --------------------------------------------------------------- |
| Agregar un valor nuevo                                 | Requiere migración de **estructura** (`DROP CONSTRAINT` + `ADD CONSTRAINT`) | Requiere solo un `INSERT` (dato, no estructura) — ver sección 7 |
| Corregir/enriquecer un valor (ej. agregar descripción) | No es posible, el CHECK solo valida, no almacena metadata                   | Se agrega una columna a la tabla catálogo, o se edita la fila   |
| Costo de implementación                                | Bajo (una línea)                                                            | Medio (una tabla nueva + FK + semilla)                          |

**Regla de decisión:** usar **catálogo** cuando hay evidencia real de que la lista va a
crecer — no una posibilidad especulativa ("podría pasar algún día"), sino alguna señal
concreta: el negocio ya anticipa nuevos valores, ya existen varios casos de uso
distintos que van a necesitar variantes, o ya hay valores "futuros" nombrados en el
código/documentación aunque no estén activos todavía. Usar **CHECK** cuando la lista es
estable por naturaleza (no por pereza) — ejemplos de este proyecto: tipos de documento
de identidad en Colombia, sexo biológico, días de la semana como concepto (aunque cuáles
de esos días se usen activamente sí pueda cambiar).

Antes de decidir a partir de un diagrama o de intuición, **conviene verificar contra el
código real** cuando ya existe una implementación previa (ver sección 12) — la evidencia
de un enum de dominio con comentarios de "valores futuros", o de un `switch` exhaustivo
que ya sugiere cuántas variantes maneja el sistema, es más confiable que adivinar.

Ejemplos de este proyecto: `specialty`, `notification_type`, `audit_action`,
`audit_module`, `appointment_state`, `verification_purpose` → catálogo (evidencia real
o plausible de crecimiento). `identification_type`, `sex`, `workday`,
`scheduling_origin`, la mayoría de columnas `status` → CHECK (listas estables,
confirmadas por ausencia de evidencia de crecimiento en el código real).

---

## 10. Patrón "shared primary key" para especialización de entidades (no `INHERITS`)

Cuando una entidad tiene una superclase con datos comunes y varias especializaciones
con datos propios (ejemplo de este proyecto: `person` como base, `patient` y `doctor`
como especializaciones), la técnica usada es **shared primary key** (también conocida
como "Class Table Inheritance" en el vocabulario de modelado de datos) — **no** la
característica nativa `INHERITS` de PostgreSQL.

```sql
CREATE TABLE piedrazul.person (
    id UUID NOT NULL,
    CONSTRAINT pk_person PRIMARY KEY (id)
    -- columnas comunes...
);

CREATE TABLE piedrazul.patient (
    person_id UUID NOT NULL,
    CONSTRAINT pk_patient PRIMARY KEY (person_id),
    CONSTRAINT fk_patient_person FOREIGN KEY (person_id) REFERENCES piedrazul.person(id)
    -- columnas propias de patient...
);
```

**Por qué NO se usa `INHERITS`:** la documentación oficial de PostgreSQL advierte que
`PRIMARY KEY`, `UNIQUE` y `FOREIGN KEY` **no se heredan** entre tabla padre e hija con
`INHERITS` — cada tabla hija necesitaría repetir sus propios constraints, y no existe
forma nativa de garantizar unicidad global a través de toda la jerarquía (una fila en
`patient` podría duplicar una PK que ya existe en `person`, sin que Postgres lo impida).
Resolver esto requeriría triggers personalizados, complejidad que el patrón shared
primary key evita por completo.

**Regla:** la tabla especializada (`patient`, `doctor`) usa la misma columna como PK y
como FK hacia la superclase (`person_id`). Esto garantiza automáticamente la relación
1:1 — un `patient` no puede existir sin su `person` correspondiente, y no puede haber
dos filas de `patient` para la misma persona.

---

## 11. Relaciones N:M y ternarias: PK como combinación de FKs, sin `id` propio

Cuando una tabla representa **el hecho de una relación** (no una entidad con identidad
propia), su PK es la combinación de las FK involucradas — no lleva una columna `id`
adicional.

```sql
CREATE TABLE piedrazul.doctor_specialty (
    doctor_id      UUID        NOT NULL,
    specialty_code VARCHAR(40) NOT NULL,

    CONSTRAINT pk_doctor_specialty PRIMARY KEY (doctor_id, specialty_code),
    CONSTRAINT fk_doctor_specialty_doctor FOREIGN KEY (doctor_id) REFERENCES piedrazul.doctor(person_id),
    CONSTRAINT fk_doctor_specialty_specialty FOREIGN KEY (specialty_code) REFERENCES piedrazul.specialty(code)
);
```

**Cómo distinguir este caso del caso normal (PK simple + FK como columnas):**
la pregunta clave es _¿esta tabla representa "una cosa" con existencia propia, o
representa "el hecho de que A se relaciona con B"?_

- "Una cosa" con existencia propia (una cita, un intento de notificación, un registro
  de auditoría) → PK simple (`id`), las FK son columnas normales, **nunca** parte de
  la PK. Ejemplos: `appointment`, `notification_attempts`, `clinical_history`.
- "El hecho de la relación en sí", sin datos propios más allá de las FK que la
  componen → PK es la combinación de las FK. Ejemplos: `doctor_specialty`.

Un error común (detectado repetidamente en los diagramas de este proyecto durante el
diseño) es meter las FK de una entidad con existencia propia dentro de su PK "por
error de traducción" del diagrama — ej. `appointment` mostrando `(id, patient_id,
doctor_id)` como PK compuesta, cuando debía ser solo `id`. Ante la duda, preguntarse
si la tabla necesitaría alguna vez dos filas con las mismas FK pero distinto `id`: si
la respuesta es sí (dos citas entre el mismo doctor y paciente en fechas distintas),
la tabla es "una cosa" y necesita PK propia; si la respuesta es no (no tiene sentido
tener el mismo doctor con la misma especialidad dos veces), es una relación pura.

---

## 12. Validaciones que van en el backend, no en la base de datos

No toda regla de negocio debe convertirse en un `CHECK` constraint. Se deja la
validación al backend (capa de dominio en Spring Boot) cuando:

- **La regla depende del tiempo actual** (ej. "el teléfono de un acudiente es
  obligatorio si el paciente es menor de edad" depende de `CURRENT_DATE - birth_date`,
  que cambia con el simple paso del tiempo sin que nadie edite la fila — un CHECK con
  fecha puede comportarse de forma inesperada en actualizaciones futuras no
  relacionadas con esa regla).
- **La regla requiere consultar el estado de otra entidad relacionada** de forma más
  compleja que una FK simple (ej. "la especialidad de una cita debe ser una de las
  que realmente tiene el doctor asignado" requiere revisar `doctor_specialty`, no solo
  que el código exista en el catálogo `specialty`).
- **Ya existe una máquina de estados explícita y validada en el dominio** (ej.
  transiciones válidas de `appointment.state_code` o `notification.status`) — la base
  de datos garantiza que el valor pertenece al conjunto válido (CHECK o catálogo),
  pero no duplica la lógica de qué transiciones están permitidas.

La base de datos sigue garantizando la integridad de los valores individuales (CHECK,
catálogo, NOT NULL); lo que se deja al backend es la lógica **relacional o temporal**
que un constraint declarativo no expresa con claridad o que ya vive validada en el
dominio.

---

## 13. Investigar el código real antes de decidir sobre un rediseño

Cuando el schema nuevo reemplaza una implementación existente (no es un proyecto desde
cero), las decisiones de diseño (catálogo vs. CHECK, qué columnas son redundantes, qué
relaciones son 1:1 vs. 1:N) se verifican contra el **código real**, no solo contra
diagramas o intuición — los diagramas entidad-relación y relacionales usados durante el
diseño de este proyecto tuvieron errores de traducción repetidos (PKs compuestas
incorrectas, columnas duplicadas con nombres inconsistentes) que solo se detectaron al
revisar el código fuente.

Antes de eliminar una columna que parece redundante, o de asumir la cardinalidad de una
relación, conviene confirmar: dónde se usa en el código, si hay lógica que dependa de
ella, si hay evidencia de intención futura (comentarios, TODOs, valores ya nombrados
pero no implementados), y si existen condiciones de carrera o falta de constraints que
el código actual no protege pero que la base de datos nueva sí debería cerrar.

---

## 14. Checklist rápido antes de cerrar una migración

- [ ] ¿Las tablas están en orden de dependencia (catálogo → dominio → relación N:M)?
- [ ] ¿Cada tabla tiene su comentario explicativo justo encima?
- [ ] ¿PK, FK, UNIQUE y CHECK están inline dentro del `CREATE TABLE`?
- [ ] ¿Todos los constraints tienen nombre explícito siguiendo la convención (pk*/fk*/uq*/ck*)?
- [ ] ¿Los índices de rendimiento están pegados debajo de su tabla, no en un bloque aparte?
- [ ] ¿Se evaluó si las columnas FK necesitan índice explícito (Postgres no lo crea solo)?
- [ ] ¿El schema está explícito en cada objeto, sin depender del `search_path` del rol?
- [ ] ¿Las tablas de librerías externas están en su propio archivo, separadas del dominio?
- [ ] ¿Se evitó `IF NOT EXISTS` en migraciones versionadas de Flyway?
- [ ] ¿Los catálogos de solo lectura tienen su semilla en `R__` con `ON CONFLICT DO NOTHING`?
- [ ] ¿Se aplicó el criterio de la sección 9 antes de elegir CHECK vs. catálogo (evidencia real de crecimiento, no especulación)?
- [ ] ¿Las especializaciones de entidad usan shared primary key, no `INHERITS`?
- [ ] ¿Las tablas de relación pura (N:M/ternaria) tienen PK compuesta por sus FK, sin `id` propio?
- [ ] ¿Se identificaron condiciones de carrera reales en el código que ameriten un `UNIQUE` o índice único parcial nuevo?
- [ ] Si el rediseño reemplaza un sistema existente, ¿se verificaron las decisiones clave contra el código real, no solo contra diagramas?
- [ ] ¿Si se activó una extensión, se usó `SCHEMA extensions` explícito, sin `IF NOT EXISTS`, y se verificó si sus funciones necesitan un wrapper `IMMUTABLE` para poder indexarlas?

---

## 15. Extensiones de PostgreSQL: activación y funciones de soporte `IMMUTABLE`

Activar una extensión (`pg_trgm`, `unaccent`, etc.) es un cambio **estructural**,
no un dato semilla — va en una migración **versionada** (`V__`), nunca en `R__`.

```sql
CREATE EXTENSION pg_trgm SCHEMA extensions;
CREATE EXTENSION unaccent SCHEMA extensions;
```

- **`SCHEMA extensions` siempre explícito**, nunca `public` (bloqueado desde el
  bootstrap, ver `01-init-databases.sh`) — mismo motivo que la sección 8: no
  depender de `search_path` implícito.
- **Sin `IF NOT EXISTS`**: mismo motivo que la sección 6 — Flyway garantiza
  ejecución única, un fallo a mitad de camino se resuelve explícitamente.
- Las extensiones marcadas **"trusted"** (desde PostgreSQL 13 — incluye
  `pg_trgm` y `unaccent`) las puede instalar `migration_role` sin ser
  superusuario, siempre que tenga `CREATE` sobre la base de datos. Ese permiso
  ya está concedido desde el bootstrap (`01-init-databases.sh`, sección 3-4);
  no hay que tocarlo al activar una extensión nueva.

**Gotcha a revisar siempre antes de indexar una función de una extensión:**
si la función no está marcada `IMMUTABLE` (ejemplo real: `unaccent(text)` es
`STABLE`, porque depende de un diccionario configurable en tiempo de
ejecución), Postgres rechaza usarla directo en una expresión de índice con el
error `functions in index expression must be marked IMMUTABLE`. La solución
estándar es un wrapper SQL que fije esa dependencia externa de forma explícita,
volviendo el resultado determinístico y por tanto marcable `IMMUTABLE`:

```sql
CREATE FUNCTION extensions.immutable_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
AS $$
    SELECT extensions.unaccent('extensions.unaccent', $1)
$$;
```

El wrapper se activa junto con la extensión (mismo bloque de migración), pero
el **índice funcional que lo usa va pegado a su tabla** (sección 4), nunca en
el bloque de activación:

```sql
CREATE INDEX idx_person_full_name_trgm
    ON piedrazul.person
    USING gin (extensions.immutable_unaccent(lower(first_name || ' ' || last_name)) extensions.gin_trgm_ops);
```

**Dónde ubicar la activación:** si la extensión se necesita desde el arranque
del sistema, va en un bloque inicial de la migración baseline (antes de los
catálogos). Si es para una funcionalidad agregada después, va en la migración
versionada que introduce esa funcionalidad — igual que cualquier otro cambio
estructural nuevo.

---

## Fuentes consultadas

- PostgreSQL Documentation — _5.5. Constraints_ (postgresql.org/docs/current/ddl-constraints.html)
- PostgreSQL Documentation — _CREATE INDEX_ (postgresql.org/docs/current/sql-createindex.html)
- PostgreSQL Documentation — _5.11. Inheritance_ (postgresql.org/docs/current/ddl-inherit.html)
- Bytebase — _PostgreSQL SQL Review and Style Guide_ (bytebase.com/blog/postgres-sql-review-guide)
- Devart — _Database Naming Standards in SQL_ (devart.com/blog/sql-database-naming-standards.html)
- Redgate / Phil Factor — _Flyway's Baseline Migrations Explained Simply_
- Flyway Documentation — _Migrations_ (flywaydb.org/documentation/concepts/migrations)
- Redgate Flyway Documentation — _Repeatable Migrations_
- Severalnines — _The "O" in ORDBMS: PostgreSQL Inheritance_
- PostgreSQL Documentation — _Extension Building Infrastructure_ (postgresql.org/docs/current/extend-extensions.html)
- PostgreSQL mailing list — _BUG #5781: unaccent() function should be marked IMMUTABLE_
- Peter Ullrich — _Unaccented Name Search with Postgres and Ecto_ (peterullrich.com)
