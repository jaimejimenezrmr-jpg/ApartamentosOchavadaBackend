package com.ochavada.backend.access;

import com.ochavada.backend.access.LockAccessLog.LockAccessLogBuilder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe el registro de aperturas en su propia transacción: el rastro queda aunque la petición que lo
 * genera termine en error.
 */
@Slf4j
@Component
public class LockAccessLogger {

    private static final int MAX_USER_AGENT = 512;
    private static final int MAX_MESSAGE = 500;
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$");

    private final LockAccessLogRepository repository;

    public LockAccessLogger(LockAccessLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LockAccessLogBuilder entry, String providerMessage, String clientIp, String userAgent) {
        repository.save(entry
                .providerMessage(truncate(providerMessage, MAX_MESSAGE))
                .clientIp(normalizeIp(clientIp))
                .userAgent(truncate(userAgent, MAX_USER_AGENT))
                .build());
    }

    /**
     * Acepta solo literales IPv4/IPv6; cualquier otro valor se descarta (nunca se resuelve por DNS ni se
     * deja que un valor extraño impida guardar el registro).
     */
    static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String candidate = ip.trim();
        if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        if (IPV6_CHARS.matcher(candidate).matches()) {
            try {
                // Con ':' InetAddress lo trata como literal IPv6: no hay consulta DNS.
                return InetAddress.getByName(candidate).getHostAddress();
            } catch (UnknownHostException ex) {
                log.debug("IP de cliente no válida en el registro de aperturas: {}", candidate);
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
