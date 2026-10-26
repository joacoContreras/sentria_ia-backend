package iua.edu.sentria.notificacion;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import iua.edu.sentria.business.exception.EnvioException;
import iua.edu.sentria.model.CanalNotificacion;
import iua.edu.sentria.model.TipoNotificacion;
import iua.edu.sentria.model.dto.DatosTurnoNotificacion;
import iua.edu.sentria.model.dto.MensajeNotificacion;

// Simulador de mensajería (WhatsApp/SMS): POST JSON a una URL configurable,
// p. ej. webhook.site o un mock propio. Reemplaza a un proveedor pago.
@Component
public class WebhookSender implements INotificacionSender {

    private final RestClient http;

    @Value("${sentria.notificaciones.webhook.url:}")
    private String url;

    public WebhookSender() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(10));
        http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public CanalNotificacion canal() {
        return CanalNotificacion.webhook;
    }

    @Override
    public void enviar(TipoNotificacion tipo, DatosTurnoNotificacion datos, MensajeNotificacion mensaje)
            throws EnvioException {
        if (url == null || url.isBlank()) {
            throw new EnvioException("Webhook no configurado (sentria.notificaciones.webhook.url)", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("turnoId", datos.turnoId());
        body.put("tipo", tipo.name());
        body.put("email", datos.pacienteEmail());
        body.put("telefono", datos.pacienteTelefono());
        body.put("asunto", mensaje.asunto());
        body.put("mensaje", mensaje.cuerpo());
        try {
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new EnvioException("Webhook rechazó el mensaje: " + e.getStatusCode(), false, e);
        } catch (RestClientException e) {
            throw new EnvioException("Error webhook: " + e.getMessage(), true, e);
        }
    }
}
