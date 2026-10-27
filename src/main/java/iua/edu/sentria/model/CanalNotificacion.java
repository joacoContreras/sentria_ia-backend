package iua.edu.sentria.model;

// Espejo del ENUM sentria.canal_notif. sms/whatsapp/push existen en el esquema
// pero no tienen emisor: el proyecto no integra mensajería paga.
public enum CanalNotificacion {
    email, sms, whatsapp, push, webhook
}
