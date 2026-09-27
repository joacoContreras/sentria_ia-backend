-- =====================================================================
-- SENTRIA - Control de concurrencia en reservas (RNF-01)
-- Requiere: sentria_schema.sql
--
-- Las EXCLUDE de turno son la última barrera contra el double-booking,
-- pero no alcanzan solas:
--   * validan solape, no que el slot exista en la agenda ni que no haya
--     una licencia/feriado cargándose en paralelo;
--   * los sobreturnos quedan fuera de ellas;
--   * ante una carrera devuelven un 23P01 genérico, no un error de negocio.
--
-- Estrategia: BLOQUEO PESIMISTA a nivel fila sobre los recursos que
-- definen el slot, tomados SIEMPRE en el mismo orden para evitar deadlocks:
--
--     paciente -> profesional -> consultorio -> sede -> agenda
--
--   FOR NO KEY UPDATE  paciente, profesional, consultorio: dos reservas
--                      que compiten por cualquiera de ellos se serializan.
--   FOR SHARE          sede, agenda: las reservas no se bloquean entre sí,
--                      pero sí contra quien edite la agenda o cargue un
--                      feriado/licencia (ver trigger de excepcion_agenda).
--
-- NO KEY UPDATE (y no UPDATE) para no bloquear los INSERT de otras tablas
-- que referencian esas filas por FK (toman FOR KEY SHARE).
--
-- AISLAMIENTO: las funciones están pensadas para READ COMMITTED (default
-- de PostgreSQL). En plpgsql cada sentencia toma un snapshot nuevo, así que
-- después de esperar un lock las validaciones ven lo que commiteó la otra
-- transacción. Si se llaman en REPEATABLE READ / SERIALIZABLE siguen siendo
-- correctas, pero la espera termina en 40001 en vez de en un error de negocio.
--
-- Contrato con el backend (Spring, ver TurnoBusiness):
--   ST001  slot ocupado (profesional o consultorio)       -> ConflictException    409
--   ST002  el paciente ya tiene un turno superpuesto      -> ConflictException    409
--   ST003  el horario no pertenece a ninguna agenda       -> InvalidException     422
--   ST004  licencia / feriado / bloqueo en ese horario    -> InvalidException     422
--   ST005  turno en el pasado                             -> InvalidException     422
--   ST006  paciente/profesional/consultorio/turno inexistente o inactivo -> NotFoundException 404
--   ST007  transición de estado inválida                  -> ConflictException    409
--   55P03  lock_timeout (recurso muy disputado)           -> reintenta la transacción;
--   40001, 40P01  serialización / deadlock                   agotados -> 409
-- =====================================================================

BEGIN;

SET search_path = sentria, public;

-- agenda guarda horas locales (time) y turno instantes (timestamptz):
-- esta es la zona con la que se traducen unas a otros.
CREATE OR REPLACE FUNCTION zona_institucional() RETURNS text
LANGUAGE sql IMMUTABLE AS $$ SELECT 'America/Argentina/Cordoba'::text $$;

-- ---------------------------------------------------------------------
-- reservar_turno: única puerta de entrada para crear turnos.
-- fin, sede y plan se derivan (de la agenda, el consultorio y la
-- afiliación) para que el llamador no pueda mandarlos inconsistentes.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION reservar_turno(
  p_paciente_id           bigint,
  p_profesional_id        bigint,
  p_especialidad_id       bigint,
  p_consultorio_id        bigint,
  p_inicio                timestamptz,
  p_paciente_cobertura_id bigint       DEFAULT NULL,
  p_canal                 canal_origen DEFAULT 'web',
  p_es_sobreturno         boolean      DEFAULT false,
  p_motivo_consulta       text         DEFAULT NULL,
  p_creado_por            bigint       DEFAULT NULL
) RETURNS bigint
LANGUAGE plpgsql
SET search_path = sentria, public
SET lock_timeout = '5s'
AS $$
DECLARE
  v_local    timestamp := p_inicio AT TIME ZONE zona_institucional();
  v_sede_id  bigint;
  v_plan_id  bigint;
  v_duracion smallint;
  v_fin      timestamptz;
  v_periodo  tstzrange;
  v_turno_id bigint;
BEGIN
  IF p_inicio <= now() THEN
    RAISE EXCEPTION 'No se puede reservar un turno en el pasado'
      USING ERRCODE = 'ST005';
  END IF;

  ------------------------------------------------ 1. locks (orden fijo)
  PERFORM 1 FROM paciente WHERE id = p_paciente_id AND activo
    FOR NO KEY UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Paciente % inexistente o inactivo', p_paciente_id
      USING ERRCODE = 'ST006';
  END IF;

  PERFORM 1 FROM profesional WHERE id = p_profesional_id AND activo
    FOR NO KEY UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Profesional % inexistente o inactivo', p_profesional_id
      USING ERRCODE = 'ST006';
  END IF;

  SELECT sede_id INTO v_sede_id
    FROM consultorio WHERE id = p_consultorio_id AND activo
    FOR NO KEY UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Consultorio % inexistente o inactivo', p_consultorio_id
      USING ERRCODE = 'ST006';
  END IF;

  PERFORM 1 FROM sede WHERE id = v_sede_id AND activo
    FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Sede % inactiva', v_sede_id
      USING ERRCODE = 'ST006';
  END IF;

  ------------------------------- 2. el slot existe en una agenda vigente
  -- Resta de time contra time (da interval) para no dar la vuelta a la
  -- medianoche; el módulo exige que inicio caiga en el grid de la agenda.
  SELECT a.duracion_min INTO v_duracion
    FROM agenda a
   WHERE a.profesional_id  = p_profesional_id
     AND a.especialidad_id = p_especialidad_id
     AND a.consultorio_id  = p_consultorio_id
     AND a.activo
     AND a.dia_semana = extract(dow FROM v_local)
     AND v_local::date >= a.vigente_desde
     AND (a.vigente_hasta IS NULL OR v_local::date <= a.vigente_hasta)
     AND v_local::time >= a.hora_inicio
     AND v_local::time - a.hora_inicio
         <= (a.hora_fin - a.hora_inicio) - make_interval(mins => a.duracion_min)
     AND extract(epoch FROM v_local::time - a.hora_inicio)
         % (a.duracion_min * 60) = 0
     FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'El horario % no corresponde a la agenda del profesional', v_local
      USING ERRCODE = 'ST003';
  END IF;

  v_fin     := p_inicio + make_interval(mins => v_duracion);
  v_periodo := tstzrange(p_inicio, v_fin, '[)');

  ------------------------------------------ 3. licencias y feriados
  IF EXISTS (
    SELECT 1 FROM excepcion_agenda e
     WHERE e.periodo && v_periodo
       AND (e.profesional_id IS NULL OR e.profesional_id = p_profesional_id)
       AND (e.sede_id        IS NULL OR e.sede_id        = v_sede_id)
  ) THEN
    RAISE EXCEPTION 'El profesional no atiende en ese horario (licencia, feriado o bloqueo)'
      USING ERRCODE = 'ST004';
  END IF;

  ---------------------------- 4. solapes (con los locks ya tomados)
  IF NOT p_es_sobreturno AND EXISTS (
    SELECT 1 FROM turno t
     WHERE (t.profesional_id = p_profesional_id OR t.consultorio_id = p_consultorio_id)
       AND t.periodo && v_periodo
       AND t.estado <> 'cancelado'
       AND NOT t.es_sobreturno
  ) THEN
    RAISE EXCEPTION 'El turno ya no está disponible'
      USING ERRCODE = 'ST001';
  END IF;

  IF EXISTS (
    SELECT 1 FROM turno t
     WHERE t.paciente_id = p_paciente_id
       AND t.periodo && v_periodo
       AND t.estado <> 'cancelado'
  ) THEN
    RAISE EXCEPTION 'El paciente ya tiene un turno en ese horario'
      USING ERRCODE = 'ST002';
  END IF;

  ------------------------------------------------------- 5. cobertura
  -- Las FK compuestas de turno validan pertenencia y aceptación en la sede.
  IF p_paciente_cobertura_id IS NOT NULL THEN
    SELECT plan_id INTO v_plan_id
      FROM paciente_cobertura WHERE id = p_paciente_cobertura_id;
  END IF;

  ------------------------------------------------------- 6. alta
  -- Subbloque acotado al INSERT: si alguien insertó en turno salteando
  -- esta función, la EXCLUDE igual corta y se traduce al código de negocio.
  BEGIN
    INSERT INTO turno (paciente_id, profesional_id, especialidad_id, sede_id,
                       consultorio_id, paciente_cobertura_id, plan_id,
                       inicio, fin, canal, es_sobreturno, motivo_consulta, creado_por)
    VALUES (p_paciente_id, p_profesional_id, p_especialidad_id, v_sede_id,
            p_consultorio_id, p_paciente_cobertura_id, v_plan_id,
            p_inicio, v_fin, p_canal, p_es_sobreturno, p_motivo_consulta, p_creado_por)
    RETURNING id INTO v_turno_id;
  EXCEPTION WHEN exclusion_violation THEN
    RAISE EXCEPTION 'El turno ya no está disponible'
      USING ERRCODE = 'ST001', DETAIL = SQLERRM;
  END;

  INSERT INTO turno_evento (turno_id, estado_anterior, estado_nuevo, usuario_id, canal, motivo)
  VALUES (v_turno_id, NULL, 'reservado', p_creado_por, p_canal, 'alta');

  RETURN v_turno_id;
END $$;

-- ---------------------------------------------------------------------
-- cambiar_estado_turno: SELECT ... FOR UPDATE sobre el turno para que dos
-- operadores (o el paciente y recepción) no pisen el estado del otro.
--
--   reservado -> en_espera | ausente | cancelado
--   en_espera -> atendido  | ausente | cancelado
--   atendido, ausente, cancelado: finales
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION cambiar_estado_turno(
  p_turno_id   bigint,
  p_nuevo      estado_turno,
  p_usuario_id bigint       DEFAULT NULL,
  p_canal      canal_origen DEFAULT NULL,
  p_motivo     text         DEFAULT NULL
) RETURNS void
LANGUAGE plpgsql
SET search_path = sentria, public
SET lock_timeout = '5s'
AS $$
DECLARE
  v_actual estado_turno;
BEGIN
  SELECT estado INTO v_actual FROM turno WHERE id = p_turno_id
    FOR NO KEY UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Turno % inexistente', p_turno_id
      USING ERRCODE = 'ST006';
  END IF;

  IF NOT (
       (v_actual = 'reservado' AND p_nuevo IN ('en_espera','ausente','cancelado'))
    OR (v_actual = 'en_espera' AND p_nuevo IN ('atendido','ausente','cancelado'))
  ) THEN
    RAISE EXCEPTION 'Transición inválida: % -> %', v_actual, p_nuevo
      USING ERRCODE = 'ST007';
  END IF;

  UPDATE turno SET estado = p_nuevo, actualizado_en = now()
   WHERE id = p_turno_id;

  INSERT INTO turno_evento (turno_id, estado_anterior, estado_nuevo, usuario_id, canal, motivo)
  VALUES (p_turno_id, v_actual, p_nuevo, p_usuario_id, p_canal, p_motivo);
END $$;

-- ---------------------------------------------------------------------
-- Licencias y feriados: toman el mismo lock que las reservas sobre el
-- profesional / la sede. Así, cuando la excepción commitea, toda reserva
-- que la haya "esquivado" ya está commiteada y aparece al listar los
-- turnos afectados; y ninguna reserva posterior puede ignorarla.
-- Orden profesional -> sede, igual que en reservar_turno.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION excepcion_agenda_lock() RETURNS trigger
LANGUAGE plpgsql
SET search_path = sentria, public
AS $$
BEGIN
  IF NEW.profesional_id IS NOT NULL THEN
    PERFORM 1 FROM profesional WHERE id = NEW.profesional_id FOR NO KEY UPDATE;
  END IF;
  IF NEW.sede_id IS NOT NULL THEN
    PERFORM 1 FROM sede WHERE id = NEW.sede_id FOR NO KEY UPDATE;
  END IF;
  RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS excepcion_agenda_lock_trg ON excepcion_agenda;
CREATE TRIGGER excepcion_agenda_lock_trg
  BEFORE INSERT OR UPDATE OF profesional_id, sede_id, periodo ON excepcion_agenda
  FOR EACH ROW EXECUTE FUNCTION excepcion_agenda_lock();

-- ---------------------------------------------------------------------
-- Permisos (descomentar cuando exista el rol de la aplicación): el
-- backend no escribe turno directo, solo a través de las funciones.
-- ---------------------------------------------------------------------
-- REVOKE INSERT, UPDATE, DELETE ON turno, turno_evento FROM sentria_app;
-- ALTER FUNCTION reservar_turno(bigint,bigint,bigint,bigint,timestamptz,bigint,canal_origen,boolean,text,bigint) SECURITY DEFINER;
-- ALTER FUNCTION cambiar_estado_turno(bigint,estado_turno,bigint,canal_origen,text) SECURITY DEFINER;
-- GRANT EXECUTE ON FUNCTION reservar_turno(bigint,bigint,bigint,bigint,timestamptz,bigint,canal_origen,boolean,text,bigint) TO sentria_app;
-- GRANT EXECUTE ON FUNCTION cambiar_estado_turno(bigint,estado_turno,bigint,canal_origen,text) TO sentria_app;

COMMIT;
