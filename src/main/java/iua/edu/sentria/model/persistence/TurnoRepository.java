package iua.edu.sentria.model.persistence;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import iua.edu.sentria.model.Turno;

public interface TurnoRepository extends JpaRepository<Turno, Long> {

    // Los CAST fijan el tipo de cada parámetro: sin ellos un null llega sin
    // tipo y PostgreSQL no puede resolver la firma de la función.
    @Query(value = """
            SELECT sentria.reservar_turno(
                CAST(:pacienteId          AS bigint),
                CAST(:profesionalId       AS bigint),
                CAST(:especialidadId      AS bigint),
                CAST(:consultorioId       AS bigint),
                CAST(:inicio              AS timestamptz),
                CAST(:pacienteCoberturaId AS bigint),
                CAST(:canal               AS sentria.canal_origen),
                CAST(:esSobreturno        AS boolean),
                CAST(:motivoConsulta      AS text),
                CAST(:creadoPor           AS bigint))
            """, nativeQuery = true)
    Long reservarTurno(@Param("pacienteId") Long pacienteId,
            @Param("profesionalId") Long profesionalId,
            @Param("especialidadId") Long especialidadId,
            @Param("consultorioId") Long consultorioId,
            @Param("inicio") OffsetDateTime inicio,
            @Param("pacienteCoberturaId") Long pacienteCoberturaId,
            @Param("canal") String canal,
            @Param("esSobreturno") boolean esSobreturno,
            @Param("motivoConsulta") String motivoConsulta,
            @Param("creadoPor") Long creadoPor);

    // La función devuelve void: se consulta como tabla para obtener una fila.
    @Query(value = """
            SELECT 1 FROM sentria.cambiar_estado_turno(
                CAST(:turnoId   AS bigint),
                CAST(:estado    AS sentria.estado_turno),
                CAST(:usuarioId AS bigint),
                CAST(:canal     AS sentria.canal_origen),
                CAST(:motivo    AS text))
            """, nativeQuery = true)
    Integer cambiarEstadoTurno(@Param("turnoId") Long turnoId,
            @Param("estado") String estado,
            @Param("usuarioId") Long usuarioId,
            @Param("canal") String canal,
            @Param("motivo") String motivo);
}
