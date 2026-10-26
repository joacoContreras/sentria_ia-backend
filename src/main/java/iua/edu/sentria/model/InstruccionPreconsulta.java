package iua.edu.sentria.model;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Catálogo que mantiene la institución; especialidadId null = recomendación general.
@Entity
@Table(name = "instruccion_preconsulta")
@Immutable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class InstruccionPreconsulta {
    @Id
    private Long id;
    private Long especialidadId;
    private String titulo;
    private String detalle;
    private int orden;
    private boolean activo;
}
