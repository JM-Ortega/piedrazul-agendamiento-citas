-- =====================================================================
-- R__seed_catalogs.sql
-- =====================================================================
-- Datos semilla de los catálogos de solo lectura definidos en V001.
-- Flyway reaplica este archivo automáticamente cada vez que su
-- checksum cambia, por lo que todo INSERT aquí debe ser idempotente
-- (ON CONFLICT DO NOTHING). Nunca usar INSERT plano.
--
-- Esta migración solo aplica a catálogos que la aplicación consulta
-- por FK pero nunca modifica en tiempo de ejecución. No usar este
-- archivo para datos que el backend vaya a actualizar (esos van en
-- una migración versionada V__, ver convenio sección 7).
-- =====================================================================


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
    ('ATENDIDA', 'Atendida'),
    ('CANCELADA', 'Cancelada'),
    ('NO_ASISTIO', 'No Asistió'),
    ('REPROGRAMADA', 'Reprogramada')
ON CONFLICT (code) DO NOTHING;


-- ---------------------------------------------------------------------
-- notification_type
-- ---------------------------------------------------------------------
INSERT INTO piedrazul.notification_type (code, name) VALUES
    ('APPOINTMENT_SCHEDULED', 'Cita Agendada'),
    ('APPOINTMENT_REMINDER_2_DAYS', 'Recordatorio de Cita (2 días antes)'),
    ('OTP_CODE', 'Código de Verificación (OTP)')
ON CONFLICT (code) DO NOTHING;


-- ---------------------------------------------------------------------
-- verification_purpose
-- ---------------------------------------------------------------------
INSERT INTO piedrazul.verification_purpose (code, name) VALUES
    ('LINK_PATIENT_ACCOUNT', 'Vincular Cuenta de Paciente')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- audit_module
-- ---------------------------------------------------------------------
INSERT INTO piedrazul.audit_module (code, name) VALUES
    ('CITAS', 'Citas'),
    ('USUARIOS', 'Usuarios'),
    ('HISTORIAS_CLINICAS', 'Historias Clínicas'),
    ('SEGURIDAD', 'Seguridad')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- audit_action
-- ---------------------------------------------------------------------
INSERT INTO piedrazul.audit_action (code, name, audit_module_code) VALUES
    ('CITA_AGENDADA',              'Cita agendada',                       'CITAS'),
    ('CITA_REAGENDADA',            'Cita reagendada',                     'CITAS'),
    ('USUARIO_CREADO',             'Usuario creado',                      'USUARIOS'),
    ('USUARIO_MODIFICADO',         'Usuario modificado',                  'USUARIOS'),
    ('USUARIO_DESACTIVADO',        'Usuario desactivado',                 'USUARIOS'),
    ('USUARIO_ACTIVADO',           'Usuario activado',                    'USUARIOS'),
    ('HISTORIA_CLINICA_CREADA',    'Historia clínica creada',             'HISTORIAS_CLINICAS'),
    ('HISTORIA_CLINICA_CONSULTADA','Historia clínica consultada',         'HISTORIAS_CLINICAS'),
    ('LOGIN_EXITOSO',              'Inicio de sesión exitoso',            'SEGURIDAD'),
    ('LOGIN_FALLIDO',              'Intento de inicio de sesión fallido', 'SEGURIDAD')
ON CONFLICT (code) DO NOTHING;