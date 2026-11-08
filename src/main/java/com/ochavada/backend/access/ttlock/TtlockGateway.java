package com.ochavada.backend.access.ttlock;

import com.ochavada.backend.access.ttlock.TtlockDtos.LockDetail;
import com.ochavada.backend.access.ttlock.TtlockDtos.LockPage;
import com.ochavada.backend.access.ttlock.TtlockDtos.LockSummary;
import com.ochavada.backend.access.ttlock.TtlockDtos.OperationResult;
import com.ochavada.backend.access.ttlock.TtlockDtos.WithError;
import com.ochavada.backend.common.error.ServiceUnavailableException;
import feign.FeignException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fachada sobre {@link TtlockApiClient}: añade credenciales y el parámetro {@code date} obligatorio,
 * interpreta {@code errcode} y renueva el token una vez si TTLock lo rechaza.
 */
@Slf4j
@Component
public class TtlockGateway {

    /** Códigos de TTLock que indican token caducado o inválido. */
    private static final Set<Integer> TOKEN_ERRORS = Set.of(10003, 10004);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    private final TtlockApiClient client;
    private final TtlockTokenManager tokenManager;
    private final TtlockProperties properties;
    private final Clock clock;

    public TtlockGateway(TtlockApiClient client, TtlockTokenManager tokenManager, TtlockProperties properties,
                         Clock clock) {
        this.client = client;
        this.tokenManager = tokenManager;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Apertura remota. No lanza excepción ante un fallo de la cerradura: devuelve el resultado para que se
     * registre en la auditoría y se informe al huésped.
     */
    public UnlockResult unlock(long lockId) {
        requireConfigured();
        long start = System.nanoTime();
        try {
            OperationResult result = withToken(token -> client.unlock(Map.of(
                    "clientId", properties.clientId(), "accessToken", token,
                    "lockId", lockId, "date", clock.millis())));
            int latency = (int) ((System.nanoTime() - start) / 1_000_000);
            if (result != null && result.isOk()) {
                return UnlockResult.success(latency);
            }
            Integer code = result == null ? null : result.errcode();
            log.warn("TTLock rechazó la apertura de la cerradura {}: {} {}", lockId, code, result == null ? "" : result.errmsg());
            return UnlockResult.failure(code, describe(code, result == null ? null : result.errmsg()), latency);
        } catch (FeignException ex) {
            int latency = (int) ((System.nanoTime() - start) / 1_000_000);
            log.error("Fallo de comunicación con TTLock abriendo la cerradura {}: {}", lockId, ex.getMessage());
            return UnlockResult.failure(null, "No se pudo contactar con el sistema de cerraduras", latency);
        }
    }

    /** Todas las cerraduras de la cuenta TTLock (descubrimiento e importación). */
    public List<LockSummary> listAccountLocks() {
        requireConfigured();
        List<LockSummary> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            int pageNo = page;
            LockPage result = call(token -> client.listLocks(properties.clientId(), token, pageNo, PAGE_SIZE,
                    clock.millis()));
            if (result.list() != null) {
                all.addAll(result.list());
            }
            if (result.pages() == null || pageNo >= result.pages()) {
                break;
            }
        }
        return all;
    }

    public LockDetail lockDetail(long lockId) {
        requireConfigured();
        return call(token -> client.lockDetail(properties.clientId(), token, lockId, clock.millis()));
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    // ------------------------------------------------------------------ internos

    private <T extends WithError> T call(Function<String, T> operation) {
        try {
            T result = withToken(operation);
            if (result == null || !result.isOk()) {
                Integer code = result == null ? null : result.errcode();
                throw new TtlockException(describe(code, result == null ? null : result.errmsg()), code, null);
            }
            return result;
        } catch (FeignException ex) {
            throw new TtlockException("No se pudo contactar con el sistema de cerraduras", null, ex);
        }
    }

    /** Ejecuta la operación; si TTLock rechaza el token, lo renueva y reintenta una sola vez. */
    private <T extends WithError> T withToken(Function<String, T> operation) {
        T result = operation.apply(tokenManager.accessToken());
        if (result != null && result.errcode() != null && TOKEN_ERRORS.contains(result.errcode())) {
            log.info("TTLock rechazó el token ({}); se renueva y se reintenta", result.errcode());
            tokenManager.invalidate();
            result = operation.apply(tokenManager.accessToken());
        }
        return result;
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ServiceUnavailableException("access.provider_not_configured", "Apertura remota no disponible",
                    "La apertura remota de cerraduras no está configurada");
        }
    }

    /** Mensajes comprensibles para los errores habituales de TTLock. */
    static String describe(Integer code, String providerMessage) {
        if (code == null) {
            return "La cerradura no respondió";
        }
        return switch (code) {
            case -2012 -> "La cerradura no está conectada al gateway";
            case -3002, -3003 -> "El gateway de la cerradura no está disponible en este momento";
            case -3 -> "Parámetros no válidos para la cerradura";
            case -4043 -> "La cerradura no admite apertura remota (actívela en la app TTLock)";
            case 1 -> "La operación falló en la cerradura";
            default -> "Error de la cerradura (" + code + (providerMessage == null ? "" : ": " + providerMessage) + ")";
        };
    }

    public record UnlockResult(boolean success, Integer errorCode, String message, int latencyMs) {

        static UnlockResult success(int latencyMs) {
            return new UnlockResult(true, null, null, latencyMs);
        }

        static UnlockResult failure(Integer code, String message, int latencyMs) {
            return new UnlockResult(false, code, message, latencyMs);
        }
    }
}
