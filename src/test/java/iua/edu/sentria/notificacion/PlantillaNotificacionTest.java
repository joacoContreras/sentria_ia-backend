package iua.edu.sentria.notificacion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import iua.edu.sentria.model.InstruccionPreconsulta;
import iua.edu.sentria.model.TipoNotificacion;
import iua.edu.sentria.model.dto.DatosTurnoNotificacion;
import iua.edu.sentria.model.dto.MensajeNotificacion;

class PlantillaNotificacionTest {

    private final PlantillaNotificacion plantilla = new PlantillaNotificacion("https://portal.sentria/", 24);

    // lunes 2 de noviembre de 2026, 10:20 en Córdoba (UTC-3)
    private final OffsetDateTime inicio = OffsetDateTime.of(2026, 11, 2, 13, 20, 0, 0, ZoneOffset.UTC);

    private final DatosTurnoNotificacion datos = new DatosTurnoNotificacion(7L, "reservado", inicio, 3L,
            "Ecografía", "Juan", "juan@test.com", null, "Ana Pérez", "Central",
            "Av. Siempre Viva 1, Córdoba", "C1", "2");

    private final List<InstruccionPreconsulta> ayuno = List.of(
            new InstruccionPreconsulta(1L, 3L, "Ingesta de agua", "Tomar 1 litro de agua 1 hora antes.", 1, true));

    private final List<InstruccionPreconsulta> generales = List.of(
            new InstruccionPreconsulta(2L, null, "Documentación", "Traé tu DNI.", 1, true));

    @Test
    void confirmacionIncluyeDatosDelTurnoYEnlace() {
        MensajeNotificacion m = plantilla.render(TipoNotificacion.confirmacion, datos, ayuno, true,
                inicio.minusDays(3));

        assertTrue(m.asunto().startsWith("Turno confirmado: Ecografía"));
        assertTrue(m.cuerpo().contains("Ana Pérez"));
        assertTrue(m.cuerpo().contains("lunes 2 de noviembre de 2026"));
        assertTrue(m.cuerpo().contains("10:20 hs"), "la hora se muestra en la zona institucional");
        assertTrue(m.cuerpo().contains("Central - Av. Siempre Viva 1, Córdoba"));
        assertTrue(m.cuerpo().contains("C1 (piso 2)"));
        assertTrue(m.cuerpo().contains("https://portal.sentria/turnos/7"));
        assertTrue(m.cuerpo().contains("hasta 24 horas antes"));
    }

    @Test
    void conPreparacionEspecificaMuestraElBloqueYElDescargo() {
        String cuerpo = plantilla.render(TipoNotificacion.recordatorio, datos, ayuno, true,
                inicio.minusDays(1)).cuerpo();

        assertTrue(cuerpo.contains("PREPARACIÓN NECESARIA"));
        assertTrue(cuerpo.contains("Ingesta de agua: Tomar 1 litro de agua 1 hora antes."));
        assertTrue(cuerpo.contains("no reemplazan las de tu médico"));
    }

    @Test
    void sinPreparacionEspecificaMuestraRecomendacionesGenerales() {
        String cuerpo = plantilla.render(TipoNotificacion.recordatorio, datos, generales, false,
                inicio.minusDays(1)).cuerpo();

        assertFalse(cuerpo.contains("PREPARACIÓN NECESARIA"));
        assertTrue(cuerpo.contains("RECOMENDACIONES PARA EL DÍA DEL TURNO"));
        assertTrue(cuerpo.contains("Documentación: Traé tu DNI."));
    }

    @Test
    void sinNingunaIndicacionOmiteElBloque() {
        String cuerpo = plantilla.render(TipoNotificacion.recordatorio, datos, List.of(), false,
                inicio.minusDays(1)).cuerpo();

        assertFalse(cuerpo.contains("PREPARACIÓN"));
        assertFalse(cuerpo.contains("RECOMENDACIONES"));
    }

    @Test
    void aMenosDe24HorasAvisaQueNoSePuedeModificarDesdeElPortal() {
        String cuerpo = plantilla.render(TipoNotificacion.confirmacion, datos, ayuno, true,
                inicio.minusHours(5)).cuerpo();

        assertTrue(cuerpo.contains("ya no puede modificarse desde el portal"));
        assertFalse(cuerpo.contains("hasta 24 horas antes"));
    }
}
