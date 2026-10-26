package iua.edu.sentria.notificacion;

import iua.edu.sentria.business.exception.EnvioException;
import iua.edu.sentria.model.CanalNotificacion;
import iua.edu.sentria.model.TipoNotificacion;
import iua.edu.sentria.model.dto.DatosTurnoNotificacion;
import iua.edu.sentria.model.dto.MensajeNotificacion;

public interface INotificacionSender {
    CanalNotificacion canal();

    void enviar(TipoNotificacion tipo, DatosTurnoNotificacion datos, MensajeNotificacion mensaje)
            throws EnvioException;
}
