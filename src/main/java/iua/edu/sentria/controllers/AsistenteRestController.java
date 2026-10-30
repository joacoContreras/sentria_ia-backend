package iua.edu.sentria.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import iua.edu.sentria.business.IAsistenteBusiness;
import iua.edu.sentria.business.exception.BusinessException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.model.dto.ChatRequest;
import iua.edu.sentria.util.IStandartResponseBusiness;

@RestController
@RequestMapping(Constants.URL_IA)
public class AsistenteRestController extends BaseRestController {

	@Autowired
	private IAsistenteBusiness asistenteBusiness;

	@Autowired
	private IStandartResponseBusiness response;

	@PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> chat(@RequestBody ChatRequest chat) {
		try {
			return new ResponseEntity<>(asistenteBusiness.responder(chat), HttpStatus.OK);
		} catch (BusinessException e) {
			// OpenAI caído o sin configurar; las emergencias se resuelven antes y no llegan acá.
			return new ResponseEntity<>(response.build(HttpStatus.SERVICE_UNAVAILABLE, e, e.getMessage()),
					HttpStatus.SERVICE_UNAVAILABLE);
		} catch (InvalidException e) {
			return new ResponseEntity<>(response.build(HttpStatus.UNPROCESSABLE_CONTENT, e, e.getMessage()),
					HttpStatus.UNPROCESSABLE_CONTENT);
		}
	}

}
