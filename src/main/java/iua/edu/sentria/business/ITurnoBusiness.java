package iua.edu.sentria.business;

import iua.edu.sentria.business.exception.BusinessException;
import iua.edu.sentria.business.exception.ConflictException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.business.exception.NotFoundException;
import iua.edu.sentria.model.Turno;
import iua.edu.sentria.model.dto.CambioEstadoTurnoRequest;
import iua.edu.sentria.model.dto.ReservaTurnoRequest;

public interface ITurnoBusiness {
    Turno load(long id) throws NotFoundException, BusinessException;

    Turno reservar(ReservaTurnoRequest reserva)
            throws NotFoundException, ConflictException, InvalidException, BusinessException;

    Turno cambiarEstado(long id, CambioEstadoTurnoRequest cambio)
            throws NotFoundException, ConflictException, InvalidException, BusinessException;
}
