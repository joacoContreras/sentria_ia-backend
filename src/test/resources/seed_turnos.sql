-- Datos mínimos para TurnoConcurrenciaTest
SET search_path = sentria, public;

INSERT INTO sede (nombre, direccion, ciudad, provincia) VALUES ('Central', 'Av. Siempre Viva 1', 'Córdoba', 'Córdoba');
INSERT INTO consultorio (sede_id, nombre) VALUES (1, 'C1');
INSERT INTO especialidad (nombre) VALUES ('Clínica');
INSERT INTO profesional (nombre, apellido, matricula) VALUES ('Ana', 'Pérez', 'MP-1');
INSERT INTO profesional_especialidad VALUES (1, 1, true);

-- Todos los días 08-20, slots de 20': así el test no depende del día en que corre.
INSERT INTO agenda (profesional_id, especialidad_id, sede_id, consultorio_id, dia_semana, hora_inicio, hora_fin, duracion_min)
SELECT 1, 1, 1, 1, d, '08:00', '20:00', 20 FROM generate_series(0, 6) d;

INSERT INTO paciente (tipo_documento, nro_documento, nombre, apellido, email)
SELECT 'dni', (20000000 + g)::text, 'Paciente', g::text, 'paciente' || g || '@test.com'
  FROM generate_series(1, 40) g;
