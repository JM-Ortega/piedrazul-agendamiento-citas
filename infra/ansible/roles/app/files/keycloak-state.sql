-- Estado técnico de Keycloak leído de su propia base (solo lectura).
--
-- Lo corre keycloak_state.yml dentro del contenedor postgres, por el socket local, como
-- POSTGRES_USER (mismo camino que la reconciliación de db_converge.yml). Variables psql:
--   kc_schema          schema de Keycloak (KC_DB_SCHEMA)
--   app_realm          realm de la aplicación
--   automation_client  clientId de la automatización en master
--   backend_client     clientId del backend en app_realm
--   owned_temp_regex   forma exacta del clientId de una autoridad temporal propia
--
-- Salida: una línea clave=valor por hecho. Nunca selecciona columnas con secretos
-- (client.secret, credential.*) ni usernames: solo existencia, conteos, ids internos,
-- clientIds técnicos, banderas y asignaciones de roles de los principales técnicos.
--
-- schema:
--   absent         no existe el schema (lo crea 01-init-databases.sh: contrato P-1 roto)
--   empty          el schema existe y no contiene ningún objeto: Keycloak nunca escribió
--   partial        hay objetos pero no la tabla realm: inicialización interrumpida o ajena
--   initialized    existe la tabla realm
-- Que exista el schema no significa nada por sí solo: P-1 lo crea vacío.

\set ON_ERROR_STOP on
\pset footer off

SELECT to_regnamespace(:'kc_schema') IS NOT NULL AS kc_schema_exists,
       to_regclass(format('%I.realm', :'kc_schema')) IS NOT NULL AS kc_initialized \gset

\if :kc_initialized

BEGIN TRANSACTION READ ONLY;
SET LOCAL search_path TO :"kc_schema";

SELECT 'schema=initialized';

SELECT 'realm=' || name FROM realm WHERE name IN ('master', :'app_realm') ORDER BY name;

-- Autoridad temporal: Keycloak marca con is_temporary_admin=true lo que crean
-- kc.sh bootstrap-admin y KC_BOOTSTRAP_ADMIN_* (cliente o usuario, siempre en master).
-- Propia solo si es inequívoca: cliente (no usuario) con el clientId exacto que generan los
-- eventos de este ciclo y exactamente los roles que le da bootstrap-admin. Todo lo demás es
-- ajeno y no se toca.
SELECT 'temporary_admin='
       || CASE WHEN c.client_id ~ :'owned_temp_regex'
                AND coalesce(r_set.roles, '') = 'admin,default-roles-master'
               THEN 'owned' ELSE 'foreign' END
       || ':client:' || c.client_id || ':' || c.id
  FROM client c
  JOIN realm r ON r.id = c.realm_id AND r.name = 'master'
  JOIN client_attributes a ON a.client_id = c.id AND a.name = 'is_temporary_admin' AND a.value = 'true'
  LEFT JOIN LATERAL (
        SELECT string_agg(CASE WHEN k.client_role THEN '?' || k.name ELSE k.name END, ',' ORDER BY k.name) AS roles
          FROM user_entity u
          JOIN user_role_mapping m ON m.user_id = u.id
          JOIN keycloak_role k ON k.id = m.role_id
         WHERE u.service_account_client_link = c.id) r_set ON true
 ORDER BY c.client_id;
-- Un usuario temporal siempre es ajeno. Se identifica solo por su id interno de Keycloak
-- (opaco), nunca por su username: puede ser un identificador personal.
SELECT 'temporary_admin=foreign:user:id-' || u.id || ':' || u.id
  FROM user_entity u
  JOIN realm r ON r.id = u.realm_id AND r.name = 'master'
  JOIN user_attribute a ON a.user_id = u.id AND a.name = 'is_temporary_admin' AND a.value = 'true'
 ORDER BY u.id;

-- Clientes técnicos: banderas que definen su forma (confidencial, solo service account)
SELECT 'client.' || t.label || '='
       || concat_ws(',',
            'enabled:' || c.enabled,
            'public:' || c.public_client,
            'bearer:' || c.bearer_only,
            'service_account:' || c.service_accounts_enabled,
            'authenticator:' || coalesce(c.client_authenticator_type, ''),
            'standard_flow:' || c.standard_flow_enabled,
            'implicit_flow:' || c.implicit_flow_enabled,
            'direct_grants:' || c.direct_access_grants_enabled)
  FROM (VALUES ('automation', 'master', :'automation_client'),
               ('backend', :'app_realm', :'backend_client')) AS t(label, realm_name, client_id)
  JOIN realm r ON r.name = t.realm_name
  JOIN client c ON c.realm_id = r.id AND c.client_id = t.client_id
 ORDER BY t.label;

-- Roles asignados directamente al service account de cada cliente técnico:
-- realm:<rol> o <clientId>:<rol>
SELECT 'roles.' || t.label || '='
       || coalesce(string_agg(
            CASE WHEN k.client_role THEN rc.client_id ELSE 'realm' END || ':' || k.name,
            ',' ORDER BY CASE WHEN k.client_role THEN rc.client_id ELSE 'realm' END, k.name), '')
  FROM (VALUES ('automation', 'master', :'automation_client'),
               ('backend', :'app_realm', :'backend_client')) AS t(label, realm_name, client_id)
  JOIN realm r ON r.name = t.realm_name
  JOIN client c ON c.realm_id = r.id AND c.client_id = t.client_id
  JOIN user_entity u ON u.service_account_client_link = c.id
  LEFT JOIN user_role_mapping m ON m.user_id = u.id
  LEFT JOIN keycloak_role k ON k.id = m.role_id
  LEFT JOIN client rc ON rc.id = k.client
 GROUP BY t.label
 ORDER BY t.label;

-- Solo informativo y solo un conteo: usuarios humanos de master con el rol admin asignado
-- directamente. Su custodia no la decide este ciclo; no son dependencia de la automatización.
SELECT 'master_admin_users=' || count(*)
  FROM user_entity u
  JOIN realm r ON r.id = u.realm_id AND r.name = 'master'
  JOIN user_role_mapping m ON m.user_id = u.id
  JOIN keycloak_role k ON k.id = m.role_id AND NOT k.client_role AND k.name = 'admin'
 WHERE u.service_account_client_link IS NULL
   AND NOT EXISTS (SELECT 1 FROM user_attribute a
                    WHERE a.user_id = u.id AND a.name = 'is_temporary_admin' AND a.value = 'true');

COMMIT;

\elif :kc_schema_exists

-- Sin tabla realm: ¿el schema está realmente vacío (como lo deja P-1) o tiene restos?
SELECT (SELECT count(*) FROM pg_class WHERE relnamespace = to_regnamespace(:'kc_schema'))
     + (SELECT count(*) FROM pg_proc WHERE pronamespace = to_regnamespace(:'kc_schema'))
     + (SELECT count(*) FROM pg_type WHERE typnamespace = to_regnamespace(:'kc_schema')) AS kc_objects \gset
SELECT 'schema=' || CASE WHEN :kc_objects = 0 THEN 'empty' ELSE 'partial' END;
SELECT 'schema_objects=' || :kc_objects;

\else

SELECT 'schema=absent';

\endif
