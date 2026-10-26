package iua.edu.sentria.model;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Auditoría de un intento de envío fallido (CA4).
@Entity
@Table(name = "notificacion_incidente")
@Getter
@NoArgsConstructor
public class NotificacionIncidente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long notificacionId;
    private int intento;
    private String error;
    @Column(insertable = false, updatable = false)
    private OffsetDateTime creadoEn;

    public NotificacionIncidente(Long notificacionId, int intento, String error) {
        this.notificacionId = notificacionId;
        this.intento = intento;
        this.error = error;
    }
}
