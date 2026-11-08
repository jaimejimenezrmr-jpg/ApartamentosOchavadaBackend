package com.ochavada.backend.notification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Remitente de los emails transaccionales ({@code app.mail.*}). El servidor SMTP se configura con las
 * propiedades estándar {@code spring.mail.*}.
 */
@Validated
@ConfigurationProperties("app.mail")
public record MailSettings(
        @NotBlank @Email @DefaultValue("apartamentosochavada@gmail.com") String from,
        @NotBlank @DefaultValue("Apartamentos Ochavada") String fromName,
        @Email String replyTo) {
}
