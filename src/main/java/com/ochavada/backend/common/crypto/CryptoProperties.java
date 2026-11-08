package com.ochavada.backend.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param credentialsKey clave AES-256 en Base64 (32 bytes) para cifrar en base de datos los tokens de
 *                       proveedores externos. Generar con {@code openssl rand -base64 32}. Solo es
 *                       obligatoria si se usan integraciones que guardan credenciales (TTLock, Beds24).
 */
@ConfigurationProperties("app.crypto")
public record CryptoProperties(String credentialsKey) {
}
