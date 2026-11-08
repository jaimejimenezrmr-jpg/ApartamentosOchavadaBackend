package com.ochavada.backend.notification;

import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.reservation.Reservation;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Plantillas de los emails de reserva en español e inglés. Todo dato procedente del huésped se escapa
 * antes de insertarlo en HTML.
 */
@Component
public class ReservationEmailComposer {

    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final Locale ENGLISH = Locale.forLanguageTag("en-GB");

    private final PropertyProperties property;

    public ReservationEmailComposer(PropertyProperties property) {
        this.property = property;
    }

    public MailMessage confirmation(Reservation r, String accessUrl) {
        boolean es = !"en".equalsIgnoreCase(r.getLocale());
        Locale locale = es ? SPANISH : ENGLISH;
        DateTimeFormatter dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale);
        NumberFormat money = NumberFormat.getCurrencyInstance(locale);
        money.setCurrency(Currency.getInstance(r.getCurrency()));

        BigDecimal pending = r.getTotalAmount().subtract(r.getPaidAmount()).max(BigDecimal.ZERO);
        String name = r.getHolder().getFirstName();
        String checkIn = dates.format(r.getCheckInDate()) + " (" + property.checkInTime() + ")";
        String checkOut = dates.format(r.getCheckOutDate()) + " (" + property.checkOutTime() + ")";
        String guests = r.getAdults() + (es ? " adultos" : " adults")
                + (r.getChildren() > 0 ? ", " + r.getChildren() + (es ? " niños" : " children") : "");

        String subject = es
                ? "Reserva confirmada " + r.getCode() + " · " + property.name()
                : "Booking confirmed " + r.getCode() + " · " + property.name();

        String[][] rows = {
                {es ? "Localizador" : "Booking reference", r.getCode()},
                {es ? "Alojamiento" : "Accommodation", r.getRoomType().getName()},
                {es ? "Llegada" : "Check-in", checkIn},
                {es ? "Salida" : "Check-out", checkOut},
                {es ? "Huéspedes" : "Guests", guests},
                {es ? "Total" : "Total", money.format(r.getTotalAmount())},
                {es ? "Pagado" : "Paid", money.format(r.getPaidAmount())},
                {es ? "Pendiente en el alojamiento" : "Due at the property", money.format(pending)},
        };

        String intro = es
                ? "Hola " + name + ", tu reserva en " + property.name() + " está confirmada."
                : "Hi " + name + ", your booking at " + property.name() + " is confirmed.";
        String portal = es
                ? "Desde tu área de huésped puedes completar el check-in online antes de llegar y, durante la "
                + "estancia, abrir la puerta del edificio y de tu apartamento desde el móvil. El enlace es personal: "
                + "no lo compartas."
                : "From your guest area you can complete online check-in before arrival and, during your stay, "
                + "open the building and apartment doors from your phone. The link is personal: do not share it.";
        String button = es ? "Acceder a mi estancia" : "Open my stay";
        String contact = contactLine(es);

        StringBuilder text = new StringBuilder(intro).append("\n\n");
        for (String[] row : rows) {
            text.append(row[0]).append(": ").append(row[1]).append('\n');
        }
        text.append('\n').append(portal).append('\n').append(button).append(": ").append(accessUrl).append("\n\n")
                .append(contact).append('\n');

        StringBuilder html = new StringBuilder()
                .append("<!doctype html><html lang=\"").append(es ? "es" : "en").append("\"><body style=\"")
                .append("font-family:Arial,Helvetica,sans-serif;color:#1f2933;max-width:600px;margin:0 auto\">")
                .append("<h1 style=\"font-size:20px\">").append(esc(property.name())).append("</h1>")
                .append("<p>").append(esc(intro)).append("</p>")
                .append("<table style=\"border-collapse:collapse;width:100%\">");
        for (String[] row : rows) {
            html.append("<tr><td style=\"padding:6px 8px;color:#52606d\">").append(esc(row[0]))
                    .append("</td><td style=\"padding:6px 8px;font-weight:bold\">").append(esc(row[1]))
                    .append("</td></tr>");
        }
        html.append("</table><p>").append(esc(portal)).append("</p>")
                .append("<p><a href=\"").append(esc(accessUrl)).append("\" style=\"display:inline-block;")
                .append("padding:12px 20px;background:#0b6e4f;color:#fff;text-decoration:none;border-radius:6px\">")
                .append(esc(button)).append("</a></p>")
                .append("<p style=\"color:#52606d;font-size:13px\">").append(esc(contact)).append("</p>")
                .append("</body></html>");

        return new MailMessage(r.getHolder().getEmail(), subject, text.toString(), html.toString());
    }

    private String contactLine(boolean es) {
        StringBuilder sb = new StringBuilder(es ? "Contacto: " : "Contact: ").append(property.name());
        if (property.address() != null) {
            sb.append(" · ").append(property.address());
        }
        if (property.phone() != null) {
            sb.append(" · ").append(property.phone());
        }
        if (property.email() != null) {
            sb.append(" · ").append(property.email());
        }
        return sb.toString();
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }
}
