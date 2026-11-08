package com.ochavada.backend.notification;

/** Puerto de envío de email. Producción: {@link SmtpMailGateway}. */
public interface MailGateway {

    /** Lanza una excepción si el servidor de correo no acepta el mensaje (el outbox lo reintentará). */
    void send(MailMessage message);
}
