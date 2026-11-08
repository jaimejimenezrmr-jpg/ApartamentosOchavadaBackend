package com.ochavada.backend.security.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import com.ochavada.backend.security.AppSecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Codificador y decodificador JWT simétricos (HS256). La API es a la vez emisor y validador,
 * por lo que no se necesita un servidor de autorización externo ni JWKS.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;
    private static final int MIN_SECRET_BYTES = 32;
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final SecretKey secretKey;

    public JwtConfig(AppSecurityProperties properties) {
        this.secretKey = buildKey(properties.jwt().secret());
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(AppSecurityProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(ALGORITHM)
                .build();

        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(CLOCK_SKEW);
        timestampValidator.setClock(clock);

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(properties.jwt().issuer()),
                new JwtClaimValidator<String>(JwtClaimNames.TOKEN_TYPE, v -> TokenType.fromClaim(v).isPresent()),
                new JwtClaimValidator<Object>(org.springframework.security.oauth2.jwt.JwtClaimNames.SUB,
                        v -> v instanceof String s && !s.isBlank()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private static SecretKey buildKey(String base64Secret) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.security.jwt.secret debe estar codificado en Base64", ex);
        }
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.security.jwt.secret debe tener al menos 256 bits (32 bytes); tiene "
                    + keyBytes.length + " bytes. Genere uno con: openssl rand -base64 48");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
