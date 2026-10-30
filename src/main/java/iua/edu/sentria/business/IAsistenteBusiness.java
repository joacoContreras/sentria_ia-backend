package iua.edu.sentria.business;

import iua.edu.sentria.business.exception.BusinessException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.model.dto.ChatRequest;
import iua.edu.sentria.model.dto.RespuestaChat;

public interface IAsistenteBusiness {
    RespuestaChat responder(ChatRequest chat) throws InvalidException, BusinessException;
}
