-- =====================================================================
-- SENTRIA - Notificaciones de turnos (RF-05)
-- Requiere: sentria_schema.sql
--
-- Patrón outbox: reservar_turno y el alta de las filas de notificacion
-- comparten transacción (ver TurnoBusiness.reservar), así que todo turno
-- confirmado tiene su confirmación y su recordatorio encolados. El envío
-- ocurre DESPUÉS del commit (NotificacionBusiness): si el correo/webhook
-- falla, el turno ya está reservado y el fallo queda en
-- notificacion_incidente.
--
--   plantilla     programada_para
--   confirmacion  momento de la reserva
--   recordatorio  inicio - N horas (no se encola si ese momento ya pasó:
--                 la confirmación ya lleva las indicaciones)
--
-- Re-ejecutable: sirve tanto para un volumen nuevo (docker-entrypoint)
-- como para migrar una base existente.
-- =====================================================================

-- ADD VALUE va fuera de la transacción: el valor nuevo no se puede usar
-- en la misma transacción que lo crea.
ALTER TYPE sentria.canal_notif ADD VALUE IF NOT EXISTS 'webhook';  -- simulador, en vez de SMS pago

BEGIN;

SET search_path = sentria, public;

-- ---------------------------------------------------------------------
-- Indicaciones de preparación (ayuno, ingesta de agua, etc.). Las carga
-- la institución; el sistema las transcribe tal cual y no las genera ni
-- las interpreta (RNF-07: sin juicios ni prescripciones clínicas).
--   especialidad_id NULL = recomendación general de admisión, que se usa
--   solo cuando la especialidad no tiene indicaciones propias.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS instruccion_preconsulta (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  especialidad_id bigint,
  titulo          text NOT NULL,
  detalle         text NOT NULL,
  orden           smallint NOT NULL DEFAULT 0,
  activo          boolean NOT NULL DEFAULT true,
  creado_en       timestamptz NOT NULL DEFAULT now(),
  actualizado_en  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT instr_especialidad_fk FOREIGN KEY (especialidad_id)
    REFERENCES especialidad (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT instr_titulo_ck  CHECK (btrim(titulo)  <> ''),
  CONSTRAINT instr_detalle_ck CHECK (btrim(detalle) <> '')
);
CREATE INDEX IF NOT EXISTS instr_especialidad_idx
  ON instruccion_preconsulta (especialidad_id, orden) WHERE activo;

-- ---------------------------------------------------------------------
-- Auditoría de envíos fallidos (CA4): una fila por intento fallido.
-- notificacion.intentos cuenta los intentos; acá queda el porqué.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notificacion_incidente (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  notificacion_id bigint NOT NULL,
  intento         smallint NOT NULL,
  error           text NOT NULL,
  creado_en       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT notif_inc_notificacion_fk FOREIGN KEY (notificacion_id)
    REFERENCES notificacion (id) ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT notif_inc_intento_ck CHECK (intento BETWEEN 1 AND 10)
);
CREATE INDEX IF NOT EXISTS notif_inc_notificacion_idx
  ON notificacion_incidente (notificacion_id, creado_en);

-- ---------------------------------------------------------------------
-- Recomendaciones generales de admisión (datos iniciales).
-- Las de cada especialidad se cargan por nombre, p. ej.:
--   INSERT INTO instruccion_preconsulta (especialidad_id, titulo, detalle, orden)
--   SELECT id, 'Ayuno de 8 horas', 'No ingerir alimentos ... desde 8 horas antes.', 1
--     FROM especialidad WHERE nombre = 'Laboratorio';
-- ---------------------------------------------------------------------
INSERT INTO instruccion_preconsulta (especialidad_id, titulo, detalle, orden)
SELECT NULL, v.titulo, v.detalle, v.orden
  FROM (VALUES
    ('Llegá con anticipación',
     'Presentate en la sede 15 minutos antes del horario del turno para realizar la admisión.', 1),
    ('Documentación',
     'Traé tu DNI y la credencial de tu obra social o prepaga, si corresponde.', 2),
    ('Estudios previos',
     'Si tenés estudios o informes relacionados con la consulta, traelos.', 3)
  ) AS v(titulo, detalle, orden)
 WHERE NOT EXISTS (SELECT 1 FROM instruccion_preconsulta WHERE especialidad_id IS NULL);

COMMIT;
