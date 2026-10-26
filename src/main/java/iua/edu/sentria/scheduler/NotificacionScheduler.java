package iua.edu.sentria.scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import iua.edu.sentria.business.INotificacionBusiness;

/**
 * Despacha los recordatorios cuando llega su programada_para (CA3) y
 * reintenta los envíos que fallaron. fixedDelay: una pasada no empieza
 * hasta que terminó la anterior.
 */
@Component
@ConditionalOnProperty(name = "sentria.notificaciones.scheduler.habilitado", havingValue = "true", matchIfMissing = true)
public class NotificacionScheduler {

    @Autowired
    private INotificacionBusiness notificacionBusiness;

    @Scheduled(fixedDelayString = "${sentria.notificaciones.scheduler.intervalo-ms:60000}",
            initialDelayString = "${sentria.notificaciones.scheduler.demora-inicial-ms:15000}")
    public void despacharVencidas() {
        notificacionBusiness.despacharVencidas();
    }
}
