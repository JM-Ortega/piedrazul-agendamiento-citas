-- ---------------------------------------------------------------------
-- Tabla: clinical_history
-- Se actualizó la cantidad de caracteres permitidos para la observación
-- médica
-- ---------------------------------------------------------------------

ALTER TABLE piedrazul.clinical_history
ALTER COLUMN description TYPE VARCHAR(700);