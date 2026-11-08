package com.ochavada.backend.ses.client;

import com.ochavada.backend.ses.SesProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cliente HTTP del Servicio de Comunicación: SOAP 1.1 sobre HTTPS con autenticación básica
 * ({@code Authorization: Basic base64(usuario:contraseña)}). Si se configura un almacén de confianza,
 * se usa para validar el certificado del servicio.
 */
@Slf4j
@Component
public class SesSoapClient {

    /** Código interno para "credenciales rechazadas por HTTP" (no es un código del servicio). */
    public static final int HTTP_UNAUTHORIZED = -401;

    private final SesProperties properties;
    private final SesResponseParser parser;
    private volatile HttpClient httpClient;

    public SesSoapClient(SesProperties properties, SesResponseParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    /**
     * @throws SesTransportException ante errores de red, HTTP 5xx o respuestas no interpretables
     */
    public SesResponse send(String soapEnvelope) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint()))
                .timeout(properties.readTimeout())
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", "\"\"")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (properties.username() + ":" + properties.password()).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(soapEnvelope, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new SesTransportException("No se pudo conectar con SES.HOSPEDAJES: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SesTransportException("Envío a SES.HOSPEDAJES interrumpido", ex);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return new SesResponse(HTTP_UNAUTHORIZED, "Credenciales del servicio web rechazadas (HTTP " + status + ")",
                    null, List.of(), response.body());
        }
        if (status >= 500 && !response.body().contains("Fault")) {
            throw new SesTransportException("SES.HOSPEDAJES respondió HTTP " + status, null);
        }
        return parser.parse(response.body());
    }

    private HttpClient client() {
        HttpClient current = httpClient;
        if (current == null) {
            synchronized (this) {
                if (httpClient == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                            .connectTimeout(properties.connectTimeout())
                            .followRedirects(HttpClient.Redirect.NEVER);
                    if (properties.truststorePath() != null && !properties.truststorePath().isBlank()) {
                        builder.sslContext(sslContext(properties.truststorePath(), properties.truststorePassword()));
                    }
                    httpClient = builder.build();
                }
                current = httpClient;
            }
        }
        return current;
    }

    private static SSLContext sslContext(String path, String password) {
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            String type = path.toLowerCase(Locale.ROOT).endsWith(".jks") ? "JKS" : "PKCS12";
            KeyStore store = KeyStore.getInstance(type);
            store.load(in, password == null ? null : password.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            log.info("Usando el almacén de confianza {} para SES.HOSPEDAJES", path);
            return context;
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar el almacén de confianza de SES: " + path, ex);
        }
    }
}
