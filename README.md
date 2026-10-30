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

## Asistente IA: triage y guardrails (RF-06, RF-07, RNF-07)

`POST /api/v1/ia/chat` recibe el historial de la conversación y devuelve la respuesta del asistente:

```json
// request
{ "mensajes": [ { "rol": "usuario", "contenido": "me duele el oído y tengo fiebre leve hace 2 días" } ] }
// response
{ "tipo": "triage", "respuesta": "…", "alertaEmergencia": false,
  "reservaHabilitada": true, "especialidadSugerida": "Otorrinolaringología" }
```

`tipo`: `administrativo | triage | emergencia | rechazo_clinico | fuera_de_dominio | intento_manipulacion`.
Con `alertaEmergencia: true` el front debe mostrar el indicador de **Alerta de Emergencia** y,
con `reservaHabilitada: false`, cortar el flujo de reserva.

**Capas de seguridad** (en orden, ver `AsistenteBusiness`):

1. **Filtro determinístico de entrada** (`AnalizadorSeguridad`, patrones en `src/main/resources/ia/patrones/`),
   jerárquico: emergencia > prompt injection > pedido clínico. No usa OpenAI: la alerta de emergencia
   funciona aunque la API esté caída. Las respuestas de estas tres categorías son textos fijos.
   La inyección se busca en todo el historial, porque el cliente podría falsificar mensajes del asistente.
2. **Moderation API** de OpenAI: autolesión → emergencia; otro contenido marcado → fuera de dominio.
3. **System prompt inmutable** (`src/main/resources/ia/system-prompt.txt`): lo agrega el backend en cada
   llamada. El DTO solo admite los roles `usuario` y `asistente`, así que el cliente no puede mandar un
   mensaje de sistema. El modelo responde en JSON.
4. **Guardrail de salida**: descarta respuestas con dosis, indicaciones farmacológicas o diagnósticos
   (`salida-clinica.txt`), o con el canario del prompt (filtración del prompt). La especialidad sugerida
   tiene que existir y estar activa en `sentria.especialidad`.

**Configuración:** `OPENAI_API_KEY` por variable de entorno (nunca en el repo); modelo y parámetros
en `sentria.ia.*` de `application.properties`.

**Red teaming:**
- `AnalizadorSeguridadTest`: 37 síntomas críticos (deben activar la alerta el 100% de las veces),
  14 inyecciones, 14 pedidos de receta/diagnóstico, falsos positivos y guardrail de salida.
- `AsistenteBusinessTest`: orquestación con un modelo simulado (la emergencia no llama al modelo, historial
  falsificado, filtración del prompt, prescripción alucinada, especialidad inventada, fuera de dominio).
- `AsistenteOpenAIRedTeamTest`: contra el modelo real; solo corre con `OPENAI_API_KEY` definida
  (`mvn test -Dtest=AsistenteOpenAIRedTeamTest`).

El filtro de emergencia prioriza no perder casos: una frase negada ("no me duele el pecho") también
activa la alerta. Se acepta ese falso positivo antes que dejar pasar una urgencia.
