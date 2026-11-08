package com.ochavada.backend.ses.client;

import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.ses.ReportEnums.CommunicationType;
import com.ochavada.backend.ses.ReportEnums.PersonRole;
import com.ochavada.backend.ses.SesProperties;
import com.ochavada.backend.ses.TravelerReport;
import com.ochavada.backend.ses.TravelerReportPerson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/**
 * Construye los mensajes del Servicio de Comunicación SES.HOSPEDAJES (especificación v3.1.2):
 * <ul>
 *     <li>XML de la solicitud (alta de partes de viajeros, alta de reservas, anulación, consulta de lotes),
 *     con la estructura y el orden de elementos de los ejemplos oficiales. Solo el elemento raíz lleva
 *     espacio de nombres en las altas (elementos hijos no cualificados).</li>
 *     <li>Empaquetado: XML UTF-8 comprimido en ZIP y codificado en Base64.</li>
 *     <li>Sobre SOAP 1.1 de la operación {@code comunicacion}.</li>
 * </ul>
 * Se usa StAX: todos los valores se escapan correctamente.
 */
@Component
public class SesXmlBuilder {

    public static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String SERVICE_NS = "http://www.soap.servicios.hospedajes.mir.es/comunicacion";
    static final String CANCEL_NS = "http://www.neg.hospedajes.mir.es/anularComunicacion";
    static final String QUERY_NS = "http://www.neg.hospedajes.mir.es/consultarComunicacion";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String ZIP_ENTRY = "solicitud.xml";

    private final SesProperties properties;
    private final PropertyProperties property;
    private final XMLOutputFactory factory = XMLOutputFactory.newFactory();

    public SesXmlBuilder(SesProperties properties, PropertyProperties property) {
        this.properties = properties;
        this.property = property;
    }

    // ------------------------------------------------------------------ solicitudes

    /** XML de alta (PV o RH) de una comunicación. */
    public String registration(TravelerReport report) {
        boolean entry = report.getCommunicationType() == CommunicationType.TRAVELER_ENTRY;
        return write(w -> {
            w.writeStartElement("alt", "peticion", entry ? properties.pvNamespace() : properties.rhNamespace());
            w.writeNamespace("alt", entry ? properties.pvNamespace() : properties.rhNamespace());
            w.writeStartElement("solicitud");
            if (entry) {
                element(w, "codigoEstablecimiento", report.getEstablishmentCode());
            }
            w.writeStartElement("comunicacion");
            if (!entry) {
                w.writeStartElement("establecimiento");
                element(w, "codigo", report.getEstablishmentCode());
                w.writeEndElement();
            }
            contract(w, report);
            for (TravelerReportPerson person : report.getPersons()) {
                person(w, person, entry);
            }
            w.writeEndElement(); // comunicacion
            w.writeEndElement(); // solicitud
            w.writeEndElement(); // peticion
        });
    }

    /** XML de anulación de comunicaciones por su código. */
    public String cancellation(Collection<String> communicationCodes) {
        return write(w -> {
            w.writeStartElement("anul", "comunicaciones", CANCEL_NS);
            w.writeNamespace("anul", CANCEL_NS);
            for (String code : communicationCodes) {
                w.writeStartElement("anul", "codigoComunicacion", CANCEL_NS);
                w.writeCharacters(code);
                w.writeEndElement();
            }
            w.writeEndElement();
        });
    }

    /** XML de consulta de lotes (máximo 10 por petición según la especificación). */
    public String batchQuery(Collection<String> batchIds) {
        return write(w -> {
            w.writeStartElement("con", "lotes", QUERY_NS);
            w.writeNamespace("con", QUERY_NS);
            for (String batch : batchIds) {
                w.writeStartElement("con", "lote", QUERY_NS);
                w.writeCharacters(batch);
                w.writeEndElement();
            }
            w.writeEndElement();
        });
    }

    // ------------------------------------------------------------------ empaquetado y sobre

    /** ZIP (una entrada {@code solicitud.xml}) + Base64. */
    public String pack(String xml) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(ZIP_ENTRY));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo comprimir la solicitud SES", ex);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /**
     * Sobre SOAP de la operación {@code comunicacion}.
     *
     * @param communicationType PV/RH; obligatorio solo en altas (A)
     */
    public String envelope(String operation, String communicationType, String packedRequest) {
        return write(w -> {
            w.writeStartElement("soapenv", "Envelope", SOAP_NS);
            w.writeNamespace("soapenv", SOAP_NS);
            w.writeNamespace("com", SERVICE_NS);
            w.writeEmptyElement("soapenv", "Header", SOAP_NS);
            w.writeStartElement("soapenv", "Body", SOAP_NS);
            w.writeStartElement("com", "comunicacionRequest", SERVICE_NS);
            w.writeStartElement("peticion");
            w.writeStartElement("cabecera");
            element(w, "codigoArrendador", properties.landlordCode());
            element(w, "aplicacion", properties.application());
            element(w, "tipoOperacion", operation);
            if ("A".equals(operation)) {
                element(w, "tipoComunicacion", communicationType);
            }
            w.writeEndElement(); // cabecera
            element(w, "solicitud", packedRequest);
            w.writeEndElement(); // peticion
            w.writeEndElement(); // comunicacionRequest
            w.writeEndElement(); // Body
            w.writeEndElement(); // Envelope
        });
    }

    // ------------------------------------------------------------------ bloques

    private void contract(XMLStreamWriter w, TravelerReport r) throws XMLStreamException {
        w.writeStartElement("contrato");
        element(w, "referencia", r.getContractReference());
        element(w, "fechaContrato", r.getContractDate().toString());
        element(w, "fechaEntrada", localDateTime(r.getCheckInAt()));
        element(w, "fechaSalida", localDateTime(r.getCheckOutAt()));
        element(w, "numPersonas", String.valueOf(r.getNumTravelers()));
        element(w, "numHabitaciones", String.valueOf(r.getNumRooms()));
        element(w, "internet", String.valueOf(r.isInternetIncluded()));
        w.writeStartElement("pago");
        element(w, "tipoPago", r.getPaymentType() == null ? "DESTI" : r.getPaymentType());
        element(w, "fechaPago", r.getPaymentDate() == null ? null : r.getPaymentDate().toString());
        element(w, "medioPago", r.getPaymentIdentification());
        element(w, "titular", r.getPaymentHolder());
        element(w, "caducidadTarjeta", r.getPaymentExpiry());
        w.writeEndElement(); // pago
        w.writeEndElement(); // contrato
    }

    private void person(XMLStreamWriter w, TravelerReportPerson p, boolean entry) throws XMLStreamException {
        w.writeStartElement("persona");
        // En el parte de viajeros el rol es siempre VI; en la reserva, TI (titular) o VI.
        element(w, "rol", entry ? PersonRole.VI.name() : p.getRole().name());
        element(w, "nombre", p.getFirstName());
        element(w, "apellido1", p.getLastName1());
        element(w, "apellido2", p.getLastName2());
        element(w, "tipoDocumento", p.getDocumentType() == null ? null : p.getDocumentType().name());
        element(w, "numeroDocumento", p.getDocumentNumber());
        element(w, "soporteDocumento", p.getDocumentSupportNumber());
        element(w, "fechaNacimiento", p.getBirthDate() == null ? null : p.getBirthDate().toString());
        element(w, "nacionalidad", p.getNationality());
        element(w, "sexo", p.getSex());
        if (p.getAddressLine() != null) {
            w.writeStartElement("direccion");
            element(w, "direccion", p.getAddressLine());
            element(w, "direccionComplementaria", p.getAddressComplement());
            boolean spain = "ESP".equals(p.getCountryCode());
            element(w, "codigoMunicipio", spain ? p.getMunicipalityCode() : null);
            element(w, "nombreMunicipio", spain ? null : p.getCity());
            element(w, "codigoPostal", p.getPostalCode());
            element(w, "pais", p.getCountryCode());
            w.writeEndElement();
        }
        element(w, "telefono", p.getPhone());
        element(w, "telefono2", p.getPhoneLandline());
        element(w, "correo", p.getEmail());
        element(w, "parentesco", p.getRelationship());
        w.writeEndElement();
    }

    /** Fecha y hora locales del establecimiento, formato AAAA-MM-DDThh:mm:ss. */
    private String localDateTime(Instant instant) {
        return DATE_TIME.format(instant.atZone(property.timeZone()));
    }

    /** Escribe el elemento solo si tiene valor (los opcionales vacíos se omiten). */
    private static void element(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value == null || value.isBlank()) {
            return;
        }
        w.writeStartElement(name);
        w.writeCharacters(value.trim());
        w.writeEndElement();
    }

    private String write(XmlBody body) {
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter w = factory.createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            body.write(w);
            w.writeEndDocument();
            w.flush();
            w.close();
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("No se pudo generar el XML para SES", ex);
        }
        return out.toString();
    }

    @FunctionalInterface
    private interface XmlBody {
        void write(XMLStreamWriter w) throws XMLStreamException;
    }
}
