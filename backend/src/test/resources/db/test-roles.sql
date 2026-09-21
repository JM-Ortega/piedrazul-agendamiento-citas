-- Rol que hace de "rol de la aplicación" en los tests: se pasa a las migraciones por el
-- placeholder ${app_role} (ver PostgresIntegrationSupport). En producción lo crea
-- infra/postgres/init/01-init-databases.sh a partir de APP_DB_USERNAME.
-- NOLOGIN y sin más permisos: solo se usa para comprobar qué puede y qué no puede hacer.
CREATE ROLE piedrazul_app_test NOLOGIN;

-- Igual que infra/postgres/init: todo lo que cree el usuario de migraciones queda con DML
-- completo para el rol de la aplicación. Así el REVOKE de V001 sobre audit_event tiene algo
-- que quitar; sin esto, el test de inmutabilidad pasaría aunque el REVOKE no existiera.
ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO piedrazul_app_test;
