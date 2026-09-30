package iua.edu.sentria.business;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import iua.edu.sentria.business.exception.BusinessException;
import iua.edu.sentria.business.exception.ConflictException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.business.exception.NotFoundException;
import iua.edu.sentria.model.CanalOrigen;
import iua.edu.sentria.model.Turno;
import iua.edu.sentria.model.dto.CambioEstadoTurnoRequest;
import iua.edu.sentria.model.dto.ReservaTurnoRequest;
import iua.edu.sentria.model.persistence.TurnoRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Los bloqueos pesimistas los toman las funciones SQL (ver
 * sentria_concurrencia.sql). Esta capa se ocupa de:
 *  - abrir la transacción en READ COMMITTED, que es donde esas funciones
 *    releen datos frescos después de esperar un lock;
 *  - reintentar la transacción COMPLETA ante 55P03 / 40001 / 40P01
 *    (reintentar solo la sentencia no sirve: la transacción ya abortó);
 *  - traducir los SQLSTATE de negocio (ST00x) a excepciones checked.
 */
@Service
@Slf4j
public class TurnoBusiness implements ITurnoBusiness {

    private static final Set<String> SQLSTATE_REINTENTABLE = Set.of("55P03", "40001", "40P01");

    @Autowired
    private TurnoRepository turnoDAO;

    private final TransactionTemplate tx;

    @Value("${sentria.turnos.reintentos:3}")
    private int reintentos;

    public TurnoBusiness(PlatformTransactionManager transactionManager) {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Transacción propia aunque el llamador tenga una abierta: si no, el
        // reintento reutilizaría una transacción que PostgreSQL ya abortó.
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Turno load(long id) throws NotFoundException, BusinessException {
        Optional<Turno> r;
        try {
            r = turnoDAO.findById(id);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw BusinessException.builder().cause(e).build();
        }
        if (r.isEmpty()) {
            throw NotFoundException.builder().message("No se encuentra el Turno id=" + id).build();
        }
        return r.get();
    }

    @Override
    public Turno reservar(ReservaTurnoRequest r)
            throws NotFoundException, ConflictException, InvalidException, BusinessException {
        CanalOrigen canal = r.canal() == null ? CanalOrigen.web : r.canal();
        return ejecutar(() -> {
            Long id = turnoDAO.reservarTurno(r.pacienteId(), r.profesionalId(), r.especialidadId(),
                    r.consultorioId(), r.inicio(), r.pacienteCoberturaId(), canal.name(),
                    r.esSobreturno(), r.motivoConsulta(), r.creadoPor());
            return turnoDAO.findById(id).orElseThrow();
        });
    }

    @Override
    public Turno cambiarEstado(long id, CambioEstadoTurnoRequest c)
            throws NotFoundException, ConflictException, InvalidException, BusinessException {
        if (c.estado() == null) {
            throw InvalidException.builder().message("Falta el estado destino").build();
        }
        return ejecutar(() -> {
            turnoDAO.cambiarEstadoTurno(id, c.estado().name(), c.usuarioId(),
                    c.canal() == null ? null : c.canal().name(), c.motivo());
            return turnoDAO.findById(id).orElseThrow();
        });
    }

    private Turno ejecutar(Supplier<Turno> operacion)
            throws NotFoundException, ConflictException, InvalidException, BusinessException {
        for (int intento = 1;; intento++) {
            try {
                return tx.execute(status -> operacion.get());
            } catch (RuntimeException e) {
                String sqlState = sqlState(e);
                if (SQLSTATE_REINTENTABLE.contains(sqlState) && intento <= reintentos) {
                    log.debug("SQLSTATE {} en intento {}, reintentando", sqlState, intento);
                    esperar(intento);
                    continue;
                }
                Exception traducida = traducir(e, sqlState);
                if (traducida instanceof NotFoundException nf) throw nf;
                if (traducida instanceof ConflictException ce) throw ce;
                if (traducida instanceof InvalidException ie) throw ie;
                throw (BusinessException) traducida;
            }
        }
    }

    private static Exception traducir(RuntimeException e, String sqlState) {
        String msg = mensajeBd(e);
        if (sqlState == null) {
            log.error(e.getMessage(), e);
            return BusinessException.builder().cause(e).build();
        }
        return switch (sqlState) {
            case "ST006" -> NotFoundException.builder().message(msg).cause(e).build();
            case "ST001", "ST002", "ST007" -> ConflictException.builder().message(msg).cause(e).build();
            case "ST003", "ST004", "ST005" -> InvalidException.builder().message(msg).cause(e).build();
            // lock / serialización / deadlock con los reintentos agotados
            case "55P03", "40001", "40P01" -> ConflictException.builder()
                    .message("El turno está siendo modificado por otra operación, reintente").cause(e).build();
            // FK (cobertura no aceptada en la sede, etc.) y CHECKs del esquema
            case "23503", "23514" -> InvalidException.builder().message(msg).cause(e).build();
            default -> {
                log.error(e.getMessage(), e);
                yield BusinessException.builder().cause(e).build();
            }
        };
    }

    // Spring/Hibernate envuelven la SQLException de PgJDBC: se busca en la cadena de causas.
    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    // Mensaje del RAISE, sin el prefijo "ERROR: " ni las líneas de contexto de PostgreSQL.
    private static String mensajeBd(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getMessage() != null) {
                return sql.getMessage().replaceFirst("^ERROR: ", "").lines().findFirst().orElse("");
            }
        }
        return e.getMessage();
    }

    // Backoff lineal con jitter para que los que chocaron no reintenten a la vez.
    private static void esperar(int intento) {
        try {
            Thread.sleep(50L * intento + ThreadLocalRandom.current().nextLong(50));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
