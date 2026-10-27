# Sentria_IA-Backend

## Notificaciones de turnos (RF-05)

Al reservar un turno (portal web o agente IA) se encolan, en la misma transacción, una
**confirmación** (inmediata) y un **recordatorio** (24 h antes; se omite si el turno es antes).
El envío ocurre después del commit: si falla, el turno queda reservado igual, el error se
registra en `sentria.notificacion_incidente` y se reintenta (hasta 3 veces, cada 5/10 min).
Un scheduler revisa cada minuto los envíos vencidos y descarta los de turnos cancelados o pasados.

- **Canales:** `email` (SMTP) o `webhook` (simulador, POST JSON). No se integran SMS pagos.
  Se elige con `sentria.notificaciones.canal`; el webhook usa `sentria.notificaciones.webhook.url`.
- **Correo en desarrollo:** `docker compose up` levanta Mailpit; los correos se ven en http://localhost:8025.
- **Indicaciones pre-consulta:** tabla `sentria.instruccion_preconsulta`. Las filas con
  `especialidad_id` se incluyen como preparación obligatoria; si la especialidad no tiene,
  se usan las de `especialidad_id IS NULL` (recomendaciones generales de admisión).
  El sistema las transcribe tal cual, sin generar contenido clínico (RNF-07).

```sql
INSERT INTO sentria.instruccion_preconsulta (especialidad_id, titulo, detalle, orden)
SELECT id, 'Ingesta de agua', 'Tomar 1 litro de agua una hora antes y no orinar hasta el estudio.', 1
  FROM sentria.especialidad WHERE nombre = 'Ecografía';
```

**Base existente:** los scripts de `docker-entrypoint-initdb.d` solo corren con el volumen vacío.
Para migrar un volumen ya creado:

```sh
docker compose exec -T db psql -U sentria -d sentria < sentria_notificaciones.sql
```
