package iua.edu.sentria.model.persistence;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import iua.edu.sentria.model.dto.DatosTurnoNotificacion;

/**
 * Consulta de lectura sobre tablas sin entidad (paciente, profesional, sede...).
 * JdbcClient en vez de una proyección nativa de JPA para que timestamptz llegue
 * como OffsetDateTime. Participa de la transacción JPA abierta.
 */
@Repository
public class DatosNotificacionDAO {

    private final JdbcClient jdbc;

    public DatosNotificacionDAO(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DatosTurnoNotificacion> datosTurno(long turnoId) {
        return jdbc.sql("""
                SELECT t.id                          AS turno_id,
                       t.estado::text                AS estado,
                       t.inicio,
                       t.especialidad_id,
                       e.nombre                      AS especialidad,
                       pa.nombre                     AS paciente_nombre,
                       pa.email::text                AS paciente_email,
                       pa.telefono::text             AS paciente_telefono,
                       pr.nombre || ' ' || pr.apellido AS profesional,
                       s.nombre                      AS sede,
                       s.direccion || ', ' || s.ciudad AS sede_direccion,
                       c.nombre                      AS consultorio,
                       c.piso                        AS consultorio_piso
                  FROM sentria.turno t
                  JOIN sentria.paciente     pa ON pa.id = t.paciente_id
                  JOIN sentria.profesional  pr ON pr.id = t.profesional_id
                  JOIN sentria.especialidad e  ON e.id  = t.especialidad_id
                  JOIN sentria.sede         s  ON s.id  = t.sede_id
                  JOIN sentria.consultorio  c  ON c.id  = t.consultorio_id
                 WHERE t.id = :turnoId
                """)
                .param("turnoId", turnoId)
                .query(DatosTurnoNotificacion.class)
                .optional();
    }
}
