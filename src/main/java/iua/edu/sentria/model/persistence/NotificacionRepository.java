package iua.edu.sentria.model.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import iua.edu.sentria.model.Notificacion;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    // Encola confirmación (ya) y recordatorio (inicio - horasAntes) del turno.
    // El recordatorio se omite si su momento ya pasó. ON CONFLICT: si la
    // reserva se reintenta, no duplica (notif_uk = turno, canal, plantilla).
    @Modifying
    @Query(value = """
            INSERT INTO sentria.notificacion (turno_id, paciente_id, canal, plantilla, programada_para)
            SELECT t.id, t.paciente_id, CAST(:canal AS sentria.canal_notif), v.plantilla, v.programada_para
              FROM sentria.turno t
             CROSS JOIN LATERAL (VALUES
                   ('confirmacion', now()),
                   ('recordatorio', t.inicio - make_interval(hours => CAST(:horasAntes AS int)))
                 ) AS v(plantilla, programada_para)
             WHERE t.id = :turnoId
               AND v.programada_para >= now()
            ON CONFLICT ON CONSTRAINT notif_uk DO NOTHING
            """, nativeQuery = true)
    int programar(@Param("turnoId") Long turnoId,
            @Param("canal") String canal,
            @Param("horasAntes") int horasAntes);

    @Query(value = """
            SELECT id FROM sentria.notificacion
             WHERE estado = 'pendiente' AND programada_para <= now()
             ORDER BY programada_para
             LIMIT :limite
            """, nativeQuery = true)
    List<Long> vencidas(@Param("limite") int limite);

    @Query(value = """
            SELECT id FROM sentria.notificacion
             WHERE turno_id = :turnoId AND estado = 'pendiente' AND programada_para <= now()
             ORDER BY programada_para
            """, nativeQuery = true)
    List<Long> vencidasDelTurno(@Param("turnoId") Long turnoId);

    // SKIP LOCKED: el envío inmediato y el scheduler (o dos instancias) pueden
    // apuntar a la misma fila; el que no obtiene el lock la saltea en vez de
    // esperar y mandarla de nuevo.
    @Query(value = """
            SELECT * FROM sentria.notificacion
             WHERE id = :id AND estado = 'pendiente'
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Notificacion> bloquearPendiente(@Param("id") Long id);
}
