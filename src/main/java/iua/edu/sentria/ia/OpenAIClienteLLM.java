package iua.edu.sentria.ia;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import iua.edu.sentria.business.exception.BusinessException;

/**
 * Chat Completions + Moderation de OpenAI vía REST. Temperatura baja y salida
 * en JSON para que la respuesta sea predecible y validable por el backend.
 */
@Component
public class OpenAIClienteLLM implements IClienteLLM {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModerationResponse(List<ModerationResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModerationResult(boolean flagged, Map<String, Boolean> categories) {
    }

    private final RestClient http;
    private final String apiKey;
    private final String modelo;
    private final String modeloModeracion;
    private final double temperatura;
    private final int maxTokens;

    public OpenAIClienteLLM(@Value("${sentria.ia.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${sentria.ia.openai.api-key:}") String apiKey,
            @Value("${sentria.ia.openai.modelo:gpt-4o-mini}") String modelo,
            @Value("${sentria.ia.openai.modelo-moderacion:omni-moderation-latest}") String modeloModeracion,
            @Value("${sentria.ia.openai.temperatura:0.2}") double temperatura,
            @Value("${sentria.ia.openai.max-tokens:500}") int maxTokens,
            @Value("${sentria.ia.openai.timeout-segundos:30}") int timeoutSegundos) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(timeoutSegundos));
        this.http = RestClient.builder().requestFactory(rf).baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.modelo = modelo;
        this.modeloModeracion = modeloModeracion;
        this.temperatura = temperatura;
        this.maxTokens = maxTokens;
    }

    @Override
    public String completar(List<MensajeLLM> mensajes) throws BusinessException {
        verificarClave();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelo);
        body.put("temperature", temperatura);
        body.put("max_completion_tokens", maxTokens);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", mensajes.stream().map(m -> Map.of("role", m.rol(), "content", m.contenido())).toList());
        try {
            ChatResponse r = http.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(ChatResponse.class);
            if (r == null || r.choices() == null || r.choices().isEmpty() || r.choices().get(0).message() == null) {
                throw new BusinessException("OpenAI devolvió una respuesta vacía");
            }
            return r.choices().get(0).message().content();
        } catch (RestClientException e) {
            throw new BusinessException("Error al consultar OpenAI: " + e.getMessage(), e);
        }
    }

    @Override
    public Moderacion moderar(String texto) throws BusinessException {
        verificarClave();
        try {
            ModerationResponse r = http.post().uri("/moderations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", modeloModeracion, "input", texto))
                    .retrieve().body(ModerationResponse.class);
            if (r == null || r.results() == null || r.results().isEmpty()) {
                return Moderacion.LIMPIA;
            }
            ModerationResult res = r.results().get(0);
            Set<String> categorias = res.categories() == null ? Set.of()
                    : res.categories().entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue()))
                            .map(Map.Entry::getKey).collect(Collectors.toSet());
            return new Moderacion(res.flagged(), categorias);
        } catch (RestClientException e) {
            throw new BusinessException("Error en la moderación de OpenAI: " + e.getMessage(), e);
        }
    }

    private void verificarClave() throws BusinessException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("Asistente IA no configurado (sentria.ia.openai.api-key)");
        }
    }
}
