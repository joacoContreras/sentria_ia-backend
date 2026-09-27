package iua.edu.sentria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import iua.edu.sentria.business.ITurnoBusiness;
import iua.edu.sentria.business.exception.ConflictException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.model.EstadoTurno;
import iua.edu.sentria.model.Turno;
import iua.edu.sentria.model.dto.CambioEstadoTurnoRequest;
import iua.edu.sentria.model.dto.ReservaTurnoRequest;

/**
 * Carreras reales contra PostgreSQL: varios hilos, cada uno con su conexión
 * y su transacción, compitiendo por el mismo slot / el mismo turno.
 */
@SpringBootTest
@Testcontainers
class TurnoConcurrenciaTest {

    // Los scripts del repo se ejecutan al crear la BD, en orden alfabético.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("sentria_schema.sql")),
                    "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("sentria_concurrencia.sql")),
                    "/docker-entrypoint-initdb.d/02_concurrencia.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("seed_turnos.sql"),
                    "/docker-entrypoint-initdb.d/03_seed.sql");

    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Cordoba");

    @Autowired
    private ITurnoBusiness turnoBusiness;

    @Autowired
    private JdbcTemplate jdbc;

    // Cada test usa su propio día para no pisarse con los demás.
    private static OffsetDateTime slot(int diasAdelante, int hora, int minuto) {
        return LocalDate.now(ZONA).plusDays(diasAdelante).atTime(hora, minuto).atZone(ZONA).toOffsetDateTime();
    }

    private static ReservaTurnoRequest reserva(long pacienteId, OffsetDateTime inicio) {
        return new ReservaTurnoRequest(pacienteId, 1L, 1L, 1L, inicio, null, null, false, null, null);
    }

    // Lanza todas las tareas a la vez (latch) y devuelve cuántas terminaron bien.
    private static int correrEnParalelo(List<Callable<Object>> tareas, List<Throwable> errores) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Object>> futuros = new ArrayList<>();
        for (Callable<Object> t : tareas) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return t.call();
            }));
        }
        largada.countDown();
        int ok = 0;
        for (Future<Object> f : futuros) {
            try {
                f.get();
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                errores.add(e.getCause());
            }
        }
        pool.shutdown();
        return ok;
    }

    @Test
    void mismoSlotEnParalelo_soloUnaReservaGana() throws Exception {
        OffsetDateTime inicio = slot(2, 10, 0);
        List<Callable<Object>> tareas = new ArrayList<>();
        for (long p = 1; p <= 15; p++) {
            long paciente = p;
            tareas.add(() -> turnoBusiness.reservar(reserva(paciente, inicio)));
        }
        List<Throwable> errores = new ArrayList<>();

        int ok = correrEnParalelo(tareas, errores);

        assertEquals(1, ok);
        assertEquals(14, errores.size());
        errores.forEach(e -> assertEquals(ConflictException.class, e.getClass(), e.toString()));
        Integer activos = jdbc.queryForObject(
                "SELECT count(*) FROM sentria.turno WHERE inicio = ? AND estado <> 'cancelado'",
                Integer.class, inicio);
        assertEquals(1, activos);
    }

    @Test
    void slotYaTomado_rechazaAlSegundo() throws Exception {
        turnoBusiness.reservar(reserva(20, slot(3, 9, 0)));
        assertThrows(ConflictException.class, () -> turnoBusiness.reservar(reserva(21, slot(3, 9, 0))));
    }

    @Test
    void horarioFueraDeGrilla_esInvalido() {
        assertThrows(InvalidException.class, () -> turnoBusiness.reservar(reserva(21, slot(4, 9, 5))));
        assertThrows(InvalidException.class, () -> turnoBusiness.reservar(reserva(21, slot(4, 21, 0))));
    }

    @Test
    void cancelacionesConcurrentes_soloUnaAplica() throws Exception {
        Turno turno = turnoBusiness.reservar(reserva(30, slot(5, 11, 0)));
        CambioEstadoTurnoRequest cancelar = new CambioEstadoTurnoRequest(EstadoTurno.cancelado, null, null, "test");
        List<Callable<Object>> tareas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tareas.add(() -> turnoBusiness.cambiarEstado(turno.getId(), cancelar));
        }
        List<Throwable> errores = new ArrayList<>();

        int ok = correrEnParalelo(tareas, errores);

        assertEquals(1, ok);
        errores.forEach(e -> assertEquals(ConflictException.class, e.getClass(), e.toString()));
        Integer eventos = jdbc.queryForObject(
                "SELECT count(*) FROM sentria.turno_evento WHERE turno_id = ? AND estado_nuevo = 'cancelado'",
                Integer.class, turno.getId());
        assertEquals(1, eventos);
        assertEquals(EstadoTurno.cancelado, turnoBusiness.load(turno.getId()).getEstado());
    }

    @Test
    void cancelarLiberaElSlot() throws Exception {
        Turno turno = turnoBusiness.reservar(reserva(31, slot(6, 12, 0)));
        turnoBusiness.cambiarEstado(turno.getId(),
                new CambioEstadoTurnoRequest(EstadoTurno.cancelado, null, null, null));
        Turno otro = turnoBusiness.reservar(reserva(32, slot(6, 12, 0)));
        assertEquals(EstadoTurno.reservado, otro.getEstado());
    }
}
