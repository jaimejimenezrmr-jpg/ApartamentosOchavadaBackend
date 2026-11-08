package com.ochavada.backend.support;

import com.ochavada.backend.notification.MailGateway;
import com.ochavada.backend.notification.MailMessage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captura los emails en memoria en lugar de enviarlos por SMTP. */
public class CapturingMailGateway implements MailGateway {

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> failuresByRecipient = new ConcurrentHashMap<>();

    @Override
    public void send(MailMessage message) {
        String recipient = message.to().toLowerCase(Locale.ROOT);
        Integer pending = failuresByRecipient.get(recipient);
        if (pending != null && pending > 0) {
            failuresByRecipient.put(recipient, pending - 1);
            throw new IllegalStateException("SMTP no disponible (simulado)");
        }
        sent.add(message);
    }

    public List<MailMessage> sentTo(String email) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList();
    }

    /** Los próximos {@code times} envíos a {@code email} fallan. */
    public void failNextFor(String email, int times) {
        failuresByRecipient.put(email.toLowerCase(Locale.ROOT), times);
    }
}
