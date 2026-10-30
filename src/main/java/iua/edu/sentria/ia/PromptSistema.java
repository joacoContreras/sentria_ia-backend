package iua.edu.sentria.ia;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * System prompt inmutable (CA1). Se lee una sola vez del classpath y el
 * backend lo antepone en cada llamada: el cliente no puede enviarlo ni
 * modificarlo (el DTO solo admite los roles usuario/asistente).
 *
 * El canario es una marca que solo existe en el prompt; si aparece en una
 * respuesta del modelo es que se filtró el prompt y la respuesta se descarta.
 */
@Component
public class PromptSistema {

    public static final String CANARIO = "SNT-7f3a9c-PROMPT";

    private final String plantilla;

    public PromptSistema() {
        try (InputStream in = new ClassPathResource("ia/system-prompt.txt").getInputStream()) {
            plantilla = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{CANARIO}}", CANARIO);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar ia/system-prompt.txt", e);
        }
    }

    /** Las especialidades salen de la BD, nunca del mensaje del paciente. */
    public String construir(List<String> especialidades) {
        String lista = especialidades.isEmpty()
                ? "- (sin datos: derivá a recepción para elegir la especialidad)"
                : especialidades.stream().map(e -> "- " + e).collect(Collectors.joining("\n"));
        return plantilla.replace("{{ESPECIALIDADES}}", lista);
    }

    public static boolean filtraPrompt(String respuesta) {
        return respuesta != null && respuesta.contains(CANARIO);
    }
}
