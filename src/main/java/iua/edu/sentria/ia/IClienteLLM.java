package iua.edu.sentria.ia;

import java.util.List;
import java.util.Set;

import iua.edu.sentria.business.exception.BusinessException;

public interface IClienteLLM {

    /** rol: system | user | assistant (vocabulario de OpenAI). */
    record MensajeLLM(String rol, String contenido) {
    }

    record Moderacion(boolean marcado, Set<String> categorias) {
        public static final Moderacion LIMPIA = new Moderacion(false, Set.of());

        public boolean autolesion() {
            return categorias.stream().anyMatch(c -> c.startsWith("self-harm"));
        }
    }

    /** Devuelve el contenido (JSON) de la respuesta del modelo. */
    String completar(List<MensajeLLM> mensajes) throws BusinessException;

    Moderacion moderar(String texto) throws BusinessException;
}
