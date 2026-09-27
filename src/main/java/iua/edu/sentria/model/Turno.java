package iua.edu.sentria.model;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Solo lectura para Hibernate: altas y cambios de estado pasan por
 * sentria.reservar_turno / sentria.cambiar_estado_turno, que toman los
 * bloqueos pesimistas y validan agenda, licencias y solapes.
 * Un save() sobre esta entidad no genera UPDATE.
 */
@Entity
@Table(name = "turno")
@Immutable
@Getter
@NoArgsConstructor
public class Turno {
    @Id
    private Long id;
    private Long pacienteId;
    private Long profesionalId;
    private Long especialidadId;
    private Long sedeId;
    private Long consultorioId;
    private Long pacienteCoberturaId;
    private Long planId;
    private OffsetDateTime inicio;
    private OffsetDateTime fin;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private EstadoTurno estado;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private CanalOrigen canal;
    private boolean esSobreturno;
    private String motivoConsulta;
    private Long creadoPor;
    private OffsetDateTime creadoEn;
    private OffsetDateTime actualizadoEn;
}
