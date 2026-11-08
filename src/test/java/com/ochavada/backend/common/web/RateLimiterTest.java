package com.ochavada.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2027-01-01T10:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(new RateLimitProperties(
            new RateLimitProperties.Limit(3, Duration.ofMinutes(10)),
            new RateLimitProperties.Limit(1, Duration.ofMinutes(1)),
            new RateLimitProperties.Limit(5, Duration.ofMinutes(10))), clock);

    @Test
    void blocksAfterLimitAndResetsAfterWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.check(RateLimiter.Bucket.CHECKOUT, "203.0.113.1");
        }
        assertThatThrownBy(() -> limiter.check(RateLimiter.Bucket.CHECKOUT, "203.0.113.1"))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(600));

        // Otra IP y otro endpoint tienen su propio contador.
        assertThatCode(() -> limiter.check(RateLimiter.Bucket.CHECKOUT, "203.0.113.2")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.check(RateLimiter.Bucket.GUEST_EXCHANGE, "203.0.113.1")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(10));
        assertThatCode(() -> limiter.check(RateLimiter.Bucket.CHECKOUT, "203.0.113.1")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
