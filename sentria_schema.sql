
BEGIN;

CREATE EXTENSION IF NOT EXISTS btree_gist;  -- EXCLUDE mezclando = y &&
CREATE EXTENSION IF NOT EXISTS citext;      -- email case-insensitive

CREATE SCHEMA IF NOT EXISTS sentria;
SET search_path = sentria, public;

-- Rango de hora del día (no existe nativo)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'timerange') THEN
    CREATE TYPE timerange AS RANGE (subtype = time);
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 1. Dominios y enumerados (integridad de dominio)
-- ---------------------------------------------------------------------
CREATE DOMAIN email_t    AS citext CHECK (VALUE ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$');
CREATE DOMAIN telefono_t AS text   CHECK (VALUE ~ '^\+?[0-9 ()-]{6,20}$');

CREATE TYPE rol_usuario    AS ENUM ('admin','recepcion','medico');
CREATE TYPE tipo_documento AS ENUM ('dni','le','lc','ci','pasaporte');
CREATE TYPE tipo_cobertura AS ENUM ('obra_social','prepaga','particular');
CREATE TYPE estado_turno   AS ENUM ('reservado','en_espera','atendido','ausente','cancelado');
CREATE TYPE canal_origen   AS ENUM ('web','bot_ia','recepcion','call_center');
CREATE TYPE tipo_excepcion AS ENUM ('licencia','feriado','ausencia','bloqueo');
CREATE TYPE nivel_triage   AS ENUM ('verde','amarillo','rojo');
CREATE TYPE conducta_triage AS ENUM ('guardia_inmediata','consulta_24h','telemedicina','autocuidado');
CREATE TYPE estado_triage  AS ENUM ('evaluado','en_espera','en_atencion','completado','derivado');
CREATE TYPE canal_notif    AS ENUM ('email','sms','whatsapp','push');
CREATE TYPE estado_notif   AS ENUM ('pendiente','enviada','fallida','cancelada');
CREATE TYPE tipo_registro_medico AS ENUM ('laboratorio','imagenologia','diagnostico','receta','evolucion');
CREATE TYPE categoria_ticket AS ENUM ('turnos','acceso','cobertura','laboratorio','triage','facturacion','otro');
CREATE TYPE prioridad_ticket AS ENUM ('normal','urgente');
CREATE TYPE estado_ticket    AS ENUM ('abierto','en_proceso','resuelto','cerrado');

-- =====================================================================
-- 2. CATÁLOGOS INSTITUCIONALES (RF-12)
--    Maestros: ON DELETE RESTRICT + baja lógica (activo).
-- =====================================================================

CREATE TABLE sede (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre         text NOT NULL,
  direccion      text NOT NULL,
  ciudad         text NOT NULL,
  provincia      text NOT NULL,
  activo         boolean NOT NULL DEFAULT true,
  creado_en      timestamptz NOT NULL DEFAULT now(),
  actualizado_en timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sede_nombre_uk UNIQUE (nombre)
);

CREATE TABLE consultorio (
  id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sede_id bigint NOT NULL,
  nombre  text NOT NULL,
  piso    text,
  activo  boolean NOT NULL DEFAULT true,
  CONSTRAINT consultorio_sede_fk FOREIGN KEY (sede_id)
    REFERENCES sede (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT consultorio_sede_nombre_uk UNIQUE (sede_id, nombre),
  -- Clave alternativa para FK COMPUESTA: prueba que el consultorio
  -- referenciado por agenda/turno pertenece a la sede declarada.
  CONSTRAINT consultorio_id_sede_uk UNIQUE (id, sede_id)
);
CREATE INDEX consultorio_sede_idx ON consultorio (sede_id);

CREATE TABLE especialidad (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre      text NOT NULL,
  descripcion text,
  activo      boolean NOT NULL DEFAULT true,
  CONSTRAINT especialidad_nombre_uk UNIQUE (nombre)
);

CREATE TABLE cobertura (
  id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre text NOT NULL,
  tipo   tipo_cobertura NOT NULL,
  cuit   text,
  activo boolean NOT NULL DEFAULT true,
  CONSTRAINT cobertura_nombre_uk UNIQUE (nombre),
  CONSTRAINT cobertura_cuit_ck CHECK (cuit IS NULL OR cuit ~ '^[0-9]{11}$')
);

CREATE TABLE plan_cobertura (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cobertura_id bigint NOT NULL,
  nombre       text NOT NULL,
  activo       boolean NOT NULL DEFAULT true,
  CONSTRAINT plan_cobertura_fk FOREIGN KEY (cobertura_id)
    REFERENCES cobertura (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT plan_cobertura_uk UNIQUE (cobertura_id, nombre)
);
CREATE INDEX plan_cobertura_idx ON plan_cobertura (cobertura_id);

-- Planes aceptados por sede (N:M explícita)
CREATE TABLE cobertura_aceptada (
  sede_id       bigint NOT NULL,
  plan_id       bigint NOT NULL,
  vigente_desde date NOT NULL DEFAULT current_date,
  vigente_hasta date,
  PRIMARY KEY (sede_id, plan_id),
  CONSTRAINT cob_acep_sede_fk FOREIGN KEY (sede_id)
    REFERENCES sede (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT cob_acep_plan_fk FOREIGN KEY (plan_id)
    REFERENCES plan_cobertura (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT cob_acep_vigencia_ck
    CHECK (vigente_hasta IS NULL OR vigente_hasta > vigente_desde)
);
CREATE INDEX cob_acep_plan_idx ON cobertura_aceptada (plan_id);

-- =====================================================================
-- 3. ACTORES
-- =====================================================================

CREATE TABLE usuario (                       -- staff interno
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email          email_t NOT NULL,
  password_hash  text NOT NULL,
  nombre         text NOT NULL,
  apellido       text NOT NULL,
  rol            rol_usuario NOT NULL,
  activo         boolean NOT NULL DEFAULT true,
  creado_en      timestamptz NOT NULL DEFAULT now(),
  actualizado_en timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT usuario_email_uk UNIQUE (email)
);

CREATE TABLE profesional (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  usuario_id     bigint,                     -- opcional: login del médico (1:1)
  nombre         text NOT NULL,
  apellido       text NOT NULL,
  matricula      text NOT NULL,
  activo         boolean NOT NULL DEFAULT true,
  creado_en      timestamptz NOT NULL DEFAULT now(),
  actualizado_en timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT profesional_matricula_uk UNIQUE (matricula),
  CONSTRAINT profesional_usuario_uk   UNIQUE (usuario_id),
  CONSTRAINT profesional_usuario_fk FOREIGN KEY (usuario_id)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- N:M médico <-> especialidad. Su PK es lo que agenda y turno usan para
-- probar que el profesional efectivamente ejerce esa especialidad.
CREATE TABLE profesional_especialidad (
  profesional_id  bigint NOT NULL,
  especialidad_id bigint NOT NULL,
  es_principal    boolean NOT NULL DEFAULT false,
  PRIMARY KEY (profesional_id, especialidad_id),
  CONSTRAINT prof_esp_profesional_fk FOREIGN KEY (profesional_id)
    REFERENCES profesional (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT prof_esp_especialidad_fk FOREIGN KEY (especialidad_id)
    REFERENCES especialidad (id) ON UPDATE CASCADE ON DELETE RESTRICT
);
CREATE INDEX prof_esp_especialidad_idx ON profesional_especialidad (especialidad_id);

CREATE TABLE paciente (                      -- RF-01
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tipo_documento   tipo_documento NOT NULL,
  nro_documento    text NOT NULL,
  nombre           text NOT NULL,
  apellido         text NOT NULL,
  fecha_nacimiento date,                     -- el registro web no la pide
  email            email_t,
  telefono         telefono_t,
  password_hash    text,                     -- NULL = alta por bot, sin cuenta
  terminos_aceptados_en timestamptz,         -- Ley 25.326
  activo           boolean NOT NULL DEFAULT true,
  creado_en        timestamptz NOT NULL DEFAULT now(),
  actualizado_en   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT paciente_documento_uk UNIQUE (tipo_documento, nro_documento),
  CONSTRAINT paciente_email_uk     UNIQUE (email),
  CONSTRAINT paciente_nro_doc_ck   CHECK (nro_documento ~ '^[A-Za-z0-9]{5,20}$'),
  CONSTRAINT paciente_fecha_nac_ck
    CHECK (fecha_nacimiento > date '1900-01-01' AND fecha_nacimiento <= current_date),
  CONSTRAINT paciente_contacto_ck  CHECK (email IS NOT NULL OR telefono IS NOT NULL),
  CONSTRAINT paciente_login_ck     CHECK (password_hash IS NULL OR email IS NOT NULL),
  -- quien tiene cuenta tuvo que aceptar los términos
  CONSTRAINT paciente_terminos_ck  CHECK (password_hash IS NULL OR terminos_aceptados_en IS NOT NULL)
);

-- Recuperación de contraseña. Se guarda el HASH del token (sha256), nunca
-- el token en claro: si se filtra la tabla, los links no sirven.
CREATE TABLE password_reset (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  paciente_id bigint NOT NULL,
  token_hash  text NOT NULL,
  expira_en   timestamptz NOT NULL,
  usado_en    timestamptz,
  creado_en   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pwd_reset_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT pwd_reset_token_uk  UNIQUE (token_hash),
  CONSTRAINT pwd_reset_expira_ck CHECK (expira_en > creado_en),
  CONSTRAINT pwd_reset_uso_ck    CHECK (usado_en IS NULL OR usado_en >= creado_en)
);
CREATE INDEX pwd_reset_paciente_idx ON password_reset (paciente_id);

CREATE TABLE paciente_cobertura (            -- afiliación (RF-01)
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  paciente_id   bigint NOT NULL,
  plan_id       bigint NOT NULL,
  nro_afiliado  text NOT NULL,
  validado      boolean NOT NULL DEFAULT false,
  vigente_desde date NOT NULL DEFAULT current_date,
  vigente_hasta date,
  CONSTRAINT pac_cob_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT pac_cob_plan_fk FOREIGN KEY (plan_id)
    REFERENCES plan_cobertura (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT pac_cob_uk          UNIQUE (paciente_id, plan_id),
  CONSTRAINT pac_cob_afiliado_uk UNIQUE (plan_id, nro_afiliado),
  -- Claves alternativas para las FK compuestas de turno:
  CONSTRAINT pac_cob_id_paciente_uk UNIQUE (id, paciente_id),
  CONSTRAINT pac_cob_id_plan_uk     UNIQUE (id, plan_id),
  CONSTRAINT pac_cob_vigencia_ck
    CHECK (vigente_hasta IS NULL OR vigente_hasta > vigente_desde)
);

-- =====================================================================
-- 4. AGENDAS (RF-10)
-- =====================================================================

CREATE TABLE agenda (                        -- plantilla semanal
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  profesional_id  bigint NOT NULL,
  especialidad_id bigint NOT NULL,
  sede_id         bigint NOT NULL,
  consultorio_id  bigint NOT NULL,
  dia_semana      smallint NOT NULL,         -- 0=domingo … 6=sábado
  hora_inicio     time NOT NULL,
  hora_fin        time NOT NULL,
  duracion_min    smallint NOT NULL DEFAULT 20,
  vigente_desde   date NOT NULL DEFAULT current_date,
  vigente_hasta   date,
  activo          boolean NOT NULL DEFAULT true,

  -- FK COMPUESTA: el par (médico, especialidad) debe existir
  CONSTRAINT agenda_prof_esp_fk FOREIGN KEY (profesional_id, especialidad_id)
    REFERENCES profesional_especialidad (profesional_id, especialidad_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  -- FK COMPUESTA: el consultorio pertenece a la sede
  CONSTRAINT agenda_consultorio_sede_fk FOREIGN KEY (consultorio_id, sede_id)
    REFERENCES consultorio (id, sede_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,

  CONSTRAINT agenda_dia_ck      CHECK (dia_semana BETWEEN 0 AND 6),
  CONSTRAINT agenda_horas_ck    CHECK (hora_fin > hora_inicio),
  CONSTRAINT agenda_duracion_ck CHECK (duracion_min BETWEEN 5 AND 240),
  CONSTRAINT agenda_vigencia_ck CHECK (vigente_hasta IS NULL OR vigente_hasta >= vigente_desde),

  CONSTRAINT agenda_prof_sin_solape EXCLUDE USING gist (
    profesional_id WITH =, dia_semana WITH =,
    timerange(hora_inicio, hora_fin, '[)') WITH &&
  ) WHERE (activo),
  CONSTRAINT agenda_consultorio_sin_solape EXCLUDE USING gist (
    consultorio_id WITH =, dia_semana WITH =,
    timerange(hora_inicio, hora_fin, '[)') WITH &&
  ) WHERE (activo)
);

CREATE TABLE excepcion_agenda (              -- licencias, feriados, ausencias
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  profesional_id bigint,                     -- NULL = feriado institucional
  sede_id        bigint,
  tipo           tipo_excepcion NOT NULL,
  periodo        tstzrange NOT NULL,
  motivo         text,
  creado_por     bigint,
  creado_en      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT exc_profesional_fk FOREIGN KEY (profesional_id)
    REFERENCES profesional (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT exc_sede_fk FOREIGN KEY (sede_id)
    REFERENCES sede (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT exc_creado_por_fk FOREIGN KEY (creado_por)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT exc_periodo_ck CHECK (NOT isempty(periodo)),
  CONSTRAINT exc_alcance_ck CHECK (profesional_id IS NOT NULL OR sede_id IS NOT NULL),
  CONSTRAINT exc_sin_solape EXCLUDE USING gist (
    profesional_id WITH =, periodo WITH &&
  ) WHERE (profesional_id IS NOT NULL)
);

-- =====================================================================
-- 5. TURNOS (RF-04, RF-09, RNF-01, RNF-02)
-- =====================================================================

CREATE TABLE turno (
  id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  paciente_id           bigint NOT NULL,
  profesional_id        bigint NOT NULL,
  especialidad_id       bigint NOT NULL,
  sede_id               bigint NOT NULL,
  consultorio_id        bigint NOT NULL,
  paciente_cobertura_id bigint,              -- NULL = particular
  plan_id               bigint,              -- desnormalizado para validar aceptación
  inicio                timestamptz NOT NULL,
  fin                   timestamptz NOT NULL,
  periodo tstzrange GENERATED ALWAYS AS (tstzrange(inicio, fin, '[)')) STORED,
  estado                estado_turno NOT NULL DEFAULT 'reservado',
  canal                 canal_origen NOT NULL DEFAULT 'web',
  es_sobreturno         boolean NOT NULL DEFAULT false,
  motivo_consulta       text,
  creado_por            bigint,
  creado_en             timestamptz NOT NULL DEFAULT now(),
  actualizado_en        timestamptz NOT NULL DEFAULT now(),

  ------------------------------------------------------------------ FKs
  CONSTRAINT turno_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT turno_creado_por_fk FOREIGN KEY (creado_por)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE SET NULL,
  -- (1) el médico ejerce esa especialidad
  CONSTRAINT turno_prof_esp_fk FOREIGN KEY (profesional_id, especialidad_id)
    REFERENCES profesional_especialidad (profesional_id, especialidad_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  -- (2) el consultorio pertenece a la sede
  CONSTRAINT turno_consultorio_sede_fk FOREIGN KEY (consultorio_id, sede_id)
    REFERENCES consultorio (id, sede_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  -- (3) la afiliación usada es de ESE paciente
  CONSTRAINT turno_cob_paciente_fk FOREIGN KEY (paciente_cobertura_id, paciente_id)
    REFERENCES paciente_cobertura (id, paciente_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  -- (4) el plan declarado es el de esa afiliación
  CONSTRAINT turno_cob_plan_fk FOREIGN KEY (paciente_cobertura_id, plan_id)
    REFERENCES paciente_cobertura (id, plan_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  -- (5) ese plan está aceptado en esa sede
  CONSTRAINT turno_plan_aceptado_fk FOREIGN KEY (sede_id, plan_id)
    REFERENCES cobertura_aceptada (sede_id, plan_id)
    ON UPDATE CASCADE ON DELETE RESTRICT,

  --------------------------------------------------------------- CHECKs
  CONSTRAINT turno_rango_ck    CHECK (fin > inicio),
  CONSTRAINT turno_duracion_ck CHECK (fin - inicio BETWEEN interval '5 minutes' AND interval '4 hours'),
  CONSTRAINT turno_cobertura_coherente_ck
    CHECK (num_nulls(paciente_cobertura_id, plan_id) IN (0, 2)),

  ------------------------------------------- ANTI DOUBLE-BOOKING (RNF-01)
  CONSTRAINT turno_prof_sin_solape EXCLUDE USING gist (
    profesional_id WITH =, periodo WITH &&
  ) WHERE (estado <> 'cancelado' AND NOT es_sobreturno),
  CONSTRAINT turno_consultorio_sin_solape EXCLUDE USING gist (
    consultorio_id WITH =, periodo WITH &&
  ) WHERE (estado <> 'cancelado' AND NOT es_sobreturno),
  CONSTRAINT turno_paciente_sin_solape EXCLUDE USING gist (
    paciente_id WITH =, periodo WITH &&
  ) WHERE (estado <> 'cancelado'),

  -- Clave alternativa para que notificacion no apunte a otro paciente
  CONSTRAINT turno_id_paciente_uk UNIQUE (id, paciente_id)
);

CREATE INDEX turno_agenda_idx      ON turno (profesional_id, inicio);
CREATE INDEX turno_paciente_idx    ON turno (paciente_id, inicio DESC);
CREATE INDEX turno_estado_idx      ON turno (estado, inicio);
CREATE INDEX turno_sede_inicio_idx ON turno (sede_id, inicio);

-- Bitácora de estados (RF-09). La escribe Express en la misma transacción.
CREATE TABLE turno_evento (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  turno_id        bigint NOT NULL,
  estado_anterior estado_turno,
  estado_nuevo    estado_turno NOT NULL,
  usuario_id      bigint,
  canal           canal_origen,
  motivo          text,
  creado_en       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT turno_evento_turno_fk FOREIGN KEY (turno_id)
    REFERENCES turno (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT turno_evento_usuario_fk FOREIGN KEY (usuario_id)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT turno_evento_transicion_ck CHECK (estado_nuevo IS DISTINCT FROM estado_anterior)
);
CREATE INDEX turno_evento_turno_idx ON turno_evento (turno_id, creado_en);

-- =====================================================================
-- 6. IA, TRIAGE Y NOTIFICACIONES (RF-03, RF-05, RF-06, RF-07, RF-13)
-- =====================================================================

CREATE TABLE sesion_ia (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  paciente_id   bigint,                      -- NULL = visitante anónimo
  canal         canal_origen NOT NULL DEFAULT 'bot_ia',
  operador_id   bigint,                      -- NOT NULL = la tomó un humano
  iniciada_en   timestamptz NOT NULL DEFAULT now(),
  finalizada_en timestamptz,                 -- NULL = activa
  CONSTRAINT sesion_ia_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT sesion_ia_operador_fk FOREIGN KEY (operador_id)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT sesion_ia_rango_ck CHECK (finalizada_en IS NULL OR finalizada_en >= iniciada_en)
);
CREATE INDEX sesion_ia_paciente_idx ON sesion_ia (paciente_id, iniciada_en DESC);

CREATE TABLE mensaje_ia (
  id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sesion_id bigint NOT NULL,
  rol       text NOT NULL,
  contenido text NOT NULL,
  tokens    integer,
  es_emergencia boolean NOT NULL DEFAULT false,   -- disparó protocolo 911
  acciones  jsonb NOT NULL DEFAULT '[]'::jsonb,   -- botones [{label, action}]
  creado_en timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT mensaje_ia_sesion_fk FOREIGN KEY (sesion_id)
    REFERENCES sesion_ia (id) ON UPDATE CASCADE ON DELETE CASCADE,
  -- 'operador' = mensaje escrito por un humano del staff
  CONSTRAINT mensaje_ia_rol_ck    CHECK (rol IN ('user','assistant','operador','system','tool')),
  CONSTRAINT mensaje_ia_tokens_ck CHECK (tokens IS NULL OR tokens >= 0),
  CONSTRAINT mensaje_ia_acciones_ck CHECK (jsonb_typeof(acciones) = 'array')
);
CREATE INDEX mensaje_ia_sesion_idx ON mensaje_ia (sesion_id, creado_en);

CREATE TABLE triage_evaluacion (
  id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sesion_id                bigint,             -- NULL = triage desde el portal
  paciente_id              bigint,
  -- ESI v4 (1 = resucitación … 5 = no urgente). Es lo que usa el front.
  nivel_esi                smallint NOT NULL,
  -- Semáforo derivado del ESI: nunca se desincroniza.
  nivel nivel_triage GENERATED ALWAYS AS (
    CASE WHEN nivel_esi <= 2 THEN 'rojo'::nivel_triage
         WHEN nivel_esi = 3  THEN 'amarillo'::nivel_triage
         ELSE 'verde'::nivel_triage END
  ) STORED,
  motivo_consulta          text NOT NULL,      -- síntoma guía
  sintomas                 jsonb NOT NULL DEFAULT '[]'::jsonb,
  -- {heart_rate, bp_systolic, bp_diastolic, o2_sat, temp, respiratory_rate}
  signos_vitales           jsonb,
  recomendacion_ia         text NOT NULL,
  conducta_sugerida        conducta_triage NOT NULL,
  estado                   estado_triage NOT NULL DEFAULT 'evaluado',
  especialidad_sugerida_id bigint,
  alerta_emergencia        boolean NOT NULL DEFAULT false,
  turno_generado_id        bigint,
  creado_en                timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT triage_sesion_fk FOREIGN KEY (sesion_id)
    REFERENCES sesion_ia (id) ON UPDATE CASCADE ON DELETE CASCADE,
  -- RESTRICT: es dato clínico, igual que registro_medico
  CONSTRAINT triage_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT triage_especialidad_fk FOREIGN KEY (especialidad_sugerida_id)
    REFERENCES especialidad (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT triage_turno_fk FOREIGN KEY (turno_generado_id)
    REFERENCES turno (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT triage_esi_ck      CHECK (nivel_esi BETWEEN 1 AND 5),
  CONSTRAINT triage_sintomas_ck CHECK (jsonb_typeof(sintomas) = 'array'),
  CONSTRAINT triage_vitales_ck  CHECK (signos_vitales IS NULL OR jsonb_typeof(signos_vitales) = 'object'),
  -- viene del chat o de un paciente logueado; nunca de la nada
  CONSTRAINT triage_origen_ck   CHECK (sesion_id IS NOT NULL OR paciente_id IS NOT NULL),
  -- RF-07: nivel rojo (ESI 1-2) obliga a alerta de emergencia
  CONSTRAINT triage_alerta_ck CHECK (nivel_esi > 2 OR alerta_emergencia)
);
CREATE INDEX triage_sesion_idx   ON triage_evaluacion (sesion_id);
CREATE INDEX triage_paciente_idx ON triage_evaluacion (paciente_id, creado_en DESC);

CREATE TABLE notificacion (                  -- RF-05
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  turno_id        bigint NOT NULL,
  paciente_id     bigint NOT NULL,
  canal           canal_notif NOT NULL,
  plantilla       text NOT NULL,
  programada_para timestamptz NOT NULL,
  enviada_en      timestamptz,
  estado          estado_notif NOT NULL DEFAULT 'pendiente',
  intentos        smallint NOT NULL DEFAULT 0,
  -- FK COMPUESTA: la notificación no puede ir a un paciente ajeno al turno
  CONSTRAINT notif_turno_paciente_fk FOREIGN KEY (turno_id, paciente_id)
    REFERENCES turno (id, paciente_id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT notif_uk           UNIQUE (turno_id, canal, plantilla),
  CONSTRAINT notif_intentos_ck  CHECK (intentos BETWEEN 0 AND 10),
  CONSTRAINT notif_enviada_ck   CHECK ((estado = 'enviada') = (enviada_en IS NOT NULL))
);
CREATE INDEX notif_pendientes_idx ON notificacion (programada_para) WHERE estado = 'pendiente';

CREATE TABLE config_ia (                     -- RF-13
  clave           text PRIMARY KEY,
  valor           jsonb NOT NULL,
  descripcion     text,
  actualizado_por bigint,
  actualizado_en  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT config_ia_usuario_fk FOREIGN KEY (actualizado_por)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE SET NULL
);

-- =====================================================================
-- 7. HISTORIA CLÍNICA Y SOPORTE
-- =====================================================================

-- Estudios e informes del paciente. ON DELETE RESTRICT: la historia
-- clínica se conserva por ley (Ley 26.529); el paciente se da de baja
-- lógica (activo = false), no se borra.
CREATE TABLE registro_medico (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  paciente_id         bigint NOT NULL,
  titulo              text NOT NULL,          -- "Hemograma completo"
  tipo                tipo_registro_medico NOT NULL,
  archivo_url         text NOT NULL,          -- S3/GCS; la URL firmada la genera Express
  archivo_nombre      text NOT NULL,
  archivo_bytes       integer NOT NULL,
  profesional_id      bigint,                 -- médico de la institución
  profesional_externo text,                   -- o nombre libre si es de afuera
  fecha_registro      date NOT NULL,          -- fecha de realización
  cargado_por         bigint,
  creado_en           timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reg_med_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT reg_med_profesional_fk FOREIGN KEY (profesional_id)
    REFERENCES profesional (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT reg_med_cargado_por_fk FOREIGN KEY (cargado_por)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT reg_med_bytes_ck       CHECK (archivo_bytes > 0),
  CONSTRAINT reg_med_fecha_ck       CHECK (fecha_registro <= current_date),
  CONSTRAINT reg_med_profesional_ck CHECK (num_nonnulls(profesional_id, profesional_externo) <= 1)
);
CREATE INDEX reg_med_paciente_idx ON registro_medico (paciente_id, fecha_registro DESC);

-- Mesa de ayuda. paciente_id NULL = contacto sin loguearse.
CREATE TABLE ticket_soporte (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  paciente_id     bigint,
  codigo          text NOT NULL,              -- 'TKT-584912', lo ve el usuario
  nombre          text NOT NULL,
  email           email_t NOT NULL,
  categoria       categoria_ticket NOT NULL,
  prioridad       prioridad_ticket NOT NULL DEFAULT 'normal',
  asunto          text NOT NULL,
  mensaje         text NOT NULL,
  adjunto_url     text,                       -- hasta 10MB (lo valida Express)
  adjunto_nombre  text,
  estado          estado_ticket NOT NULL DEFAULT 'abierto',
  asignado_a      bigint,
  creado_en       timestamptz NOT NULL DEFAULT now(),
  actualizado_en  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ticket_paciente_fk FOREIGN KEY (paciente_id)
    REFERENCES paciente (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT ticket_asignado_fk FOREIGN KEY (asignado_a)
    REFERENCES usuario (id) ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT ticket_codigo_uk  UNIQUE (codigo),
  CONSTRAINT ticket_codigo_ck  CHECK (codigo ~ '^TKT-[0-9]{6,}$'),
  CONSTRAINT ticket_adjunto_ck CHECK (num_nulls(adjunto_url, adjunto_nombre) IN (0, 2))
);
CREATE INDEX ticket_estado_idx   ON ticket_soporte (estado, creado_en);
CREATE INDEX ticket_paciente_idx ON ticket_soporte (paciente_id);

-- =====================================================================
-- 8. Verificación (auditar que ninguna FK quede sin acción declarada)
-- =====================================================================
-- SELECT conrelid::regclass AS tabla, conname, confupdtype, confdeltype
-- FROM pg_constraint
-- WHERE contype = 'f' AND connamespace = 'sentria'::regnamespace
-- ORDER BY 1;

COMMIT;
