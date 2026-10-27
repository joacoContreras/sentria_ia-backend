package iua.edu.sentria.business;

public interface INotificacionBusiness {
    /**
     * Encola confirmación y recordatorio. Debe llamarse dentro de la
     * transacción de la reserva (outbox): no abre una propia.
     */
    void programar(long turnoId);

    /** Envía, fuera del hilo del request, lo que ya venció de ese turno (la confirmación). */
    void despacharTurno(long turnoId);

    /** Envía las notificaciones vencidas de todos los turnos (lo invoca el scheduler). */
    void despacharVencidas();
}
