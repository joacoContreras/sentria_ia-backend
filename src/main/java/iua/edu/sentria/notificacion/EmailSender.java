package iua.edu.sentria.notificacion;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import iua.edu.sentria.business.exception.EnvioException;
import iua.edu.sentria.model.CanalNotificacion;
import iua.edu.sentria.model.TipoNotificacion;
import iua.edu.sentria.model.dto.DatosTurnoNotificacion;
import iua.edu.sentria.model.dto.MensajeNotificacion;

// SMTP (en desarrollo, Mailpit del docker-compose). Sin spring.mail.host no hay
// JavaMailSender: el envío falla como no reintentable y queda en auditoría.
@Component
public class EmailSender implements INotificacionSender {

    private final ObjectProvider<JavaMailSender> mailSender;

    @Value("${sentria.notificaciones.remitente}")
    private String remitente;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public CanalNotificacion canal() {
        return CanalNotificacion.email;
    }

    @Override
    public void enviar(TipoNotificacion tipo, DatosTurnoNotificacion datos, MensajeNotificacion mensaje)
            throws EnvioException {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new EnvioException("SMTP no configurado (spring.mail.host)", false);
        }
        if (datos.pacienteEmail() == null) {
            throw new EnvioException("El paciente no tiene email registrado", false);
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(remitente);
        mail.setTo(datos.pacienteEmail());
        mail.setSubject(mensaje.asunto());
        mail.setText(mensaje.cuerpo());
        try {
            sender.send(mail);
        } catch (MailException e) {
            throw new EnvioException("Error SMTP: " + e.getMessage(), true, e);
        }
    }
}
