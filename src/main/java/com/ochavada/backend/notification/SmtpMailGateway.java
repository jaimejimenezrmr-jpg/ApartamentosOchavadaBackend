package com.ochavada.backend.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Envío por SMTP (multipart/alternative: texto plano + HTML). */
@Component
public class SmtpMailGateway implements MailGateway {

    private final JavaMailSender mailSender;
    private final MailSettings settings;

    public SmtpMailGateway(JavaMailSender mailSender, MailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @Override
    public void send(MailMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(settings.from(), settings.fromName());
            if (settings.replyTo() != null && !settings.replyTo().isBlank()) {
                helper.setReplyTo(settings.replyTo());
            }
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.textBody(), message.htmlBody());
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailSendException("No se pudo componer el email para " + message.to(), ex);
        }
    }
}
