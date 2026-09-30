package iua.edu.sentria.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import iua.edu.sentria.business.ITurnoBusiness;
import iua.edu.sentria.business.exception.BusinessException;
import iua.edu.sentria.business.exception.ConflictException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.business.exception.NotFoundException;
import iua.edu.sentria.model.Turno;
import iua.edu.sentria.model.dto.CambioEstadoTurnoRequest;
import iua.edu.sentria.model.dto.ReservaTurnoRequest;
import iua.edu.sentria.util.IStandartResponseBusiness;

@RestController
@RequestMapping(Constants.URL_TURNOS)
public class TurnoRestController extends BaseRestController {

	@Autowired
	private ITurnoBusiness turnoBusiness;

	@Autowired
	private IStandartResponseBusiness response;

	@GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> load(@PathVariable long id) {
		try {
			return new ResponseEntity<>(turnoBusiness.load(id), HttpStatus.OK);
		} catch (BusinessException e) {
			return new ResponseEntity<>(response.build(HttpStatus.INTERNAL_SERVER_ERROR, e, e.getMessage()),
					HttpStatus.INTERNAL_SERVER_ERROR);
		} catch (NotFoundException e) {
			return new ResponseEntity<>(response.build(HttpStatus.NOT_FOUND, e, e.getMessage()), HttpStatus.NOT_FOUND);
		}
	}

	@PostMapping(value = "", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> reservar(@RequestBody ReservaTurnoRequest reserva) {
		try {
			Turno turno = turnoBusiness.reservar(reserva);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.set("location", Constants.URL_TURNOS + "/" + turno.getId());
			return new ResponseEntity<>(turno, responseHeaders, HttpStatus.CREATED);
		} catch (BusinessException e) {
			return new ResponseEntity<>(response.build(HttpStatus.INTERNAL_SERVER_ERROR, e, e.getMessage()),
					HttpStatus.INTERNAL_SERVER_ERROR);
		} catch (NotFoundException e) {
			return new ResponseEntity<>(response.build(HttpStatus.NOT_FOUND, e, e.getMessage()), HttpStatus.NOT_FOUND);
		} catch (ConflictException e) {
			return new ResponseEntity<>(response.build(HttpStatus.CONFLICT, e, e.getMessage()), HttpStatus.CONFLICT);
		} catch (InvalidException e) {
			return new ResponseEntity<>(response.build(HttpStatus.UNPROCESSABLE_CONTENT, e, e.getMessage()),
					HttpStatus.UNPROCESSABLE_CONTENT);
		}
	}

	@PutMapping(value = "/{id}/estado", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> cambiarEstado(@PathVariable long id, @RequestBody CambioEstadoTurnoRequest cambio) {
		try {
			return new ResponseEntity<>(turnoBusiness.cambiarEstado(id, cambio), HttpStatus.OK);
		} catch (BusinessException e) {
			return new ResponseEntity<>(response.build(HttpStatus.INTERNAL_SERVER_ERROR, e, e.getMessage()),
					HttpStatus.INTERNAL_SERVER_ERROR);
		} catch (NotFoundException e) {
			return new ResponseEntity<>(response.build(HttpStatus.NOT_FOUND, e, e.getMessage()), HttpStatus.NOT_FOUND);
		} catch (ConflictException e) {
			return new ResponseEntity<>(response.build(HttpStatus.CONFLICT, e, e.getMessage()), HttpStatus.CONFLICT);
		} catch (InvalidException e) {
			return new ResponseEntity<>(response.build(HttpStatus.UNPROCESSABLE_CONTENT, e, e.getMessage()),
					HttpStatus.UNPROCESSABLE_CONTENT);
		}
	}

}
