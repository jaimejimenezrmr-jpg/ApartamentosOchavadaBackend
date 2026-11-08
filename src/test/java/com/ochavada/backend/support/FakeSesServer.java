package com.ochavada.backend.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Imitación local del Servicio de Comunicación de SES.HOSPEDAJES: autenticación básica, solicitud en
 * ZIP+Base64 y respuestas con la forma de los ejemplos oficiales (incluidas sus erratas
 * {@code resutadoComunicacion} y {@code tipoComuniacion}, para verificar que el cliente las tolera).
 * <p>
 * Una persona con nombre {@code Rechazo} provoca un error de validación en el lote.
 */
public final class FakeSesServer {

    public static final String USERNAME = "B00000000WS";
    public static final String PASSWORD = "clave-servicio-web";
    private static final String PATH = "/hospedajes-web/ws/v1/comunicacion";

    private static final FakeSesServer INSTANCE = new FakeSesServer();

    private final HttpServer server;
    private final List<Submission> submissions = new CopyOnWriteArrayList<>();
    private final Map<String, Submission> byBatch = new ConcurrentHashMap<>();

    private FakeSesServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext(PATH, this::handle);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "fake-ses-shutdown"));
    }

    public static FakeSesServer get() {
        return INSTANCE;
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    /** Comunicaciones recibidas cuyo XML contiene el texto indicado (p. ej. el localizador). */
    public List<Submission> submissionsContaining(String text) {
        return submissions.stream().filter(s -> s.xml().contains(text)).toList();
    }

    public List<Submission> submissions() {
        return submissions;
    }

    // ------------------------------------------------------------------ servidor

    private void handle(HttpExchange exchange) throws IOException {
        String expected = "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "Unauthorized");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            Document soap = parse(body);
            String operation = text(soap, "tipoOperacion");
            String type = text(soap, "tipoComunicacion");
            String landlord = text(soap, "codigoArrendador");
            String xml = unpack(text(soap, "solicitud"));
            respond(exchange, 200, switch (operation) {
                case "A", "B" -> register(operation, type, landlord, xml);
                case "C" -> query(xml);
                default -> envelope("<respuesta><codigoRetorno>10117</codigoRetorno><descripcion>La operación sólo "
                        + "podrá tener como valores 'A', 'C' ó 'B'</descripcion></respuesta>");
            });
        } catch (IllegalArgumentException ex) {
            respond(exchange, 200, envelope("<respuesta><codigoRetorno>10111</codigoRetorno><descripcion>Formato de "
                    + "solicitud incorrecto. Ha de ser un fichero xml en UTF-8, comprimido (zip) y codificado en Base64"
                    + "</descripcion></respuesta>"));
        } catch (Exception ex) {
            respond(exchange, 500, "error: " + ex.getMessage());
        }
    }

    private String register(String operation, String type, String landlord, String xml) {
        String batch = UUID.randomUUID().toString();
        Submission submission = new Submission(batch, operation, type, landlord, xml);
        submissions.add(submission);
        byBatch.put(batch, submission);
        return envelope("<respuesta><codigoRetorno>0</codigoRetorno><descripcion>Ok</descripcion><lote>" + batch
                + "</lote></respuesta>");
    }

    private String query(String xml) throws Exception {
        Document doc = parse(xml);
        NodeList lots = doc.getElementsByTagNameNS("*", "lote");
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < lots.getLength(); i++) {
            String batch = lots.item(i).getTextContent().trim();
            Submission s = byBatch.get(batch);
            if (s == null) {
                continue;
            }
            boolean rejected = s.xml().contains("<nombre>Rechazo</nombre>");
            String communication;
            int state;
            if ("B".equals(s.operation())) {
                state = 1;
                communication = "";
            } else if (rejected) {
                state = 6;
                communication = "<resultadoComunicaciones><resutadoComunicacion><orden>1</orden>"
                        + "<tipoError>Error validación de datos</tipoError><error>Error de validación en la comunicación "
                        + "[1]: [El nombre no es válido]</error></resutadoComunicacion></resultadoComunicaciones>";
            } else {
                state = 1;
                communication = "<resultadoComunicaciones><resutadoComunicacion><orden>1</orden><codigoComunicacion>"
                        + s.communicationCode() + "</codigoComunicacion></resutadoComunicacion></resultadoComunicaciones>";
            }
            results.append("<resultado><lote>").append(batch).append("</lote><tipoComuniacion>")
                    .append(s.type() == null ? "" : s.type()).append("</tipoComuniacion><tipoOperacion>")
                    .append(s.operation()).append("</tipoOperacion><codigoEstado>").append(state)
                    .append("</codigoEstado><descEstado>Procesado</descEstado>").append(communication)
                    .append("</resultado>");
        }
        return envelope("<respuesta><codigo>0</codigo><descripcion>Ok</descripcion></respuesta>" + results);
    }

    private static String envelope(String content) {
        return "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"><SOAP-ENV:Header/>"
                + "<SOAP-ENV:Body><ns3:comunicacionResponse xmlns:ns3=\"http://www.soap.servicios.hospedajes.mir.es/comunicacion\">"
                + content + "</ns3:comunicacionResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>";
    }

    /** Base64 → ZIP → XML UTF-8; lanza IllegalArgumentException si el formato no es el exigido. */
    private static String unpack(String base64) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)),
                StandardCharsets.UTF_8)) {
            if (zip.getNextEntry() == null) {
                throw new IllegalArgumentException("ZIP vacío");
            }
            return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String text(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            nodes = doc.getElementsByTagName(localName);
        }
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/xml;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Comunicación recibida. */
    public record Submission(String batch, String operation, String type, String landlordCode, String xml) {

        /** Código de comunicación determinista por lote. */
        public String communicationCode() {
            return UUID.nameUUIDFromBytes(batch.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
}
