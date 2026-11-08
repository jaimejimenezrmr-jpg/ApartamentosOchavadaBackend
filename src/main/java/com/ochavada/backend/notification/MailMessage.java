package com.ochavada.backend.notification;

import java.util.Objects;

/** Email listo para enviar, con versión de texto plano y HTML. */
public record MailMessage(String to, String subject, String textBody, String htmlBody) {

    public MailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(textBody, "textBody");
        Objects.requireNonNull(htmlBody, "htmlBody");
    }
}
