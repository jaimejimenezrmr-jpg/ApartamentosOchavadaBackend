package com.ochavada.backend.common.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Limitador de ventana fija en memoria por (endpoint, IP). Suficiente para una única instancia del
 * backend; con varias réplicas debe complementarse con las reglas de Cloudflare.
 * <p>
 * Protege sobre todo el paso 1 del checkout, que retiene inventario: sin límite, un bot podría
 * bloquear todas las unidades con retenciones que no va a pagar.
 */
@Component
public class RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void check(Bucket bucket, String clientKey) {
        RateLimitProperties.Limit limit = switch (bucket) {
            case CHECKOUT -> properties.checkout();
            case GUEST_EXCHANGE -> properties.guestExchange();
            case GUEST_UNLOCK -> properties.guestUnlock();
        };
        Instant now = clock.instant();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.isExpired(now));
        }
        Window window = windows.compute(bucket.name() + ":" + clientKey, (key, current) ->
                current == null || current.isExpired(now)
                        ? new Window(now.plus(limit.window()), 1)
                        : new Window(current.resetAt(), current.count() + 1));
        if (window.count() > limit.requests()) {
            long retryAfter = Math.max(1, Duration.between(now, window.resetAt()).toSeconds());
            throw new TooManyRequestsException(retryAfter);
        }
    }

    public enum Bucket {
        CHECKOUT, GUEST_EXCHANGE, GUEST_UNLOCK
    }

    private record Window(Instant resetAt, int count) {
        boolean isExpired(Instant now) {
            return !resetAt.isAfter(now);
        }
    }
}
