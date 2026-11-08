package com.ochavada.backend.invoicing;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Documento imprimible de la factura (HTML autocontenido; el navegador lo guarda como PDF). Incluye las
 * menciones obligatorias del art. 6/7 RD 1619/2012.
 */
@Component
public class InvoiceDocumentRenderer {

    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", ES);

    /** Debe llamarse dentro de una transacción (lee líneas y factura rectificada). */
    public String render(Invoice i) {
        NumberFormat money = NumberFormat.getCurrencyInstance(ES);
        money.setCurrency(Currency.getInstance(i.getCurrency()));
        NumberFormat number = NumberFormat.getNumberInstance(ES);
        number.setMaximumFractionDigits(3);

        String title = switch (i.getInvoiceType()) {
            case F1 -> "Factura";
            case F2 -> "Factura simplificada";
            default -> "Factura rectificativa";
        };
        StringBuilder html = new StringBuilder(4096)
                .append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>")
                .append(esc(title + " " + i.getFullNumber())).append("</title><style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;color:#1f2933;max-width:800px;margin:24px auto;font-size:14px}")
                .append("h1{font-size:22px;margin:0}table{width:100%;border-collapse:collapse;margin-top:16px}")
                .append("th,td{padding:6px 8px;border-bottom:1px solid #d9e2ec;text-align:left}")
                .append("td.n,th.n{text-align:right}.parties{display:flex;gap:32px;margin-top:16px}")
                .append(".box{flex:1}.muted{color:#52606d;font-size:12px}.totals td{border:none}")
                .append("@media print{body{margin:0}}</style></head><body>")
                .append("<h1>").append(esc(title)).append("</h1>")
                .append("<p><strong>Nº ").append(esc(i.getFullNumber())).append("</strong> · Fecha de expedición: ")
                .append(DATE.format(i.getIssueDate()));
        if (i.getOperationDate() != null) {
            html.append(" · Fecha de operación: ").append(DATE.format(i.getOperationDate()));
        }
        html.append("</p>");
        if (i.getRectifiedInvoice() != null) {
            html.append("<p>Rectifica la factura <strong>").append(esc(i.getRectifiedInvoice().getFullNumber()))
                    .append("</strong> de ").append(DATE.format(i.getRectifiedInvoice().getIssueDate()))
                    .append(". Motivo: ").append(esc(i.getRectificationReason())).append("</p>");
        }
        html.append("<div class=\"parties\"><div class=\"box\"><div class=\"muted\">Emisor</div><strong>")
                .append(esc(i.getIssuerName())).append("</strong><br>NIF: ").append(esc(i.getIssuerTaxId()))
                .append("<br>").append(esc(i.getIssuerAddress())).append("</div>");
        if (i.getCustomerName() != null) {
            html.append("<div class=\"box\"><div class=\"muted\">Destinatario</div><strong>")
                    .append(esc(i.getCustomerName())).append("</strong>");
            if (i.getCustomerTaxId() != null) {
                html.append("<br>NIF/ID fiscal: ").append(esc(i.getCustomerTaxId()));
            }
            if (i.getCustomerAddress() != null) {
                html.append("<br>").append(esc(i.getCustomerAddress()));
            }
            html.append("</div>");
        }
        html.append("</div><table><thead><tr><th>Concepto</th><th class=\"n\">Cantidad</th>")
                .append("<th class=\"n\">Precio unit. (sin IVA)</th><th class=\"n\">Base imponible</th></tr></thead><tbody>");
        for (InvoiceLine line : i.getLines()) {
            html.append("<tr><td>").append(esc(line.getDescription())).append("</td><td class=\"n\">")
                    .append(number.format(line.getQuantity())).append("</td><td class=\"n\">")
                    .append(money.format(line.getUnitPrice())).append("</td><td class=\"n\">")
                    .append(money.format(line.getTaxableBase())).append("</td></tr>");
        }
        BigDecimal rate = i.getLines().isEmpty() ? BigDecimal.ZERO : i.getLines().getFirst().getVatRate();
        html.append("</tbody></table><table class=\"totals\">")
                .append(row("Base imponible", money.format(i.getTaxableBase())))
                .append(row("IVA " + number.format(rate) + " %", money.format(i.getVatAmount())))
                .append(row("<strong>Total</strong>", "<strong>" + money.format(i.getTotalAmount()) + "</strong>"))
                .append("</table>");
        if (i.getNotes() != null) {
            html.append("<p>").append(esc(i.getNotes())).append("</p>");
        }
        html.append("<p class=\"muted\">Huella del registro: ").append(esc(i.getVerifactuHash())).append("</p>")
                .append("</body></html>");
        return html.toString();
    }

    private static String row(String label, String value) {
        return "<tr><td class=\"n\" style=\"width:80%\">" + label + "</td><td class=\"n\">" + value + "</td></tr>";
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }
}
