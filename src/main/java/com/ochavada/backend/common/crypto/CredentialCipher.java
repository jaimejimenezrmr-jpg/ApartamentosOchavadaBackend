package com.ochavada.backend.common.crypto;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Cifrado autenticado AES-256-GCM para secretos en reposo (tokens OAuth de proveedores).
 * Formato: Base64( "v1" ‖ IV(12) ‖ ciphertext+tag(16) ). El prefijo de versión permite rotar el algoritmo.
 * <p>
 * Si la base de datos se filtra, los tokens no son utilizables sin la clave, que vive solo en el entorno.
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte[] VERSION = "v1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final CryptoProperties properties;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public CredentialCipher(CryptoProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(VERSION);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(VERSION.length + iv.length + encrypted.length)
                    .put(VERSION).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar la credencial", ex);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            byte[] version = new byte[VERSION.length];
            buffer.get(version);
            if (!Arrays.equals(version, VERSION)) {
                throw new IllegalStateException("Versión de cifrado de credenciales desconocida");
            }
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(VERSION);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | BufferUnderflowException ex) {
            throw new IllegalStateException("No se pudo descifrar la credencial (¿clave APP_CREDENTIALS_KEY distinta?)", ex);
        }
    }

    private SecretKey key() {
        SecretKey current = key;
        if (current == null) {
            synchronized (this) {
                if (key == null) {
                    key = buildKey(properties.credentialsKey());
                }
                current = key;
            }
        }
        return current;
    }

    private static SecretKey buildKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("Falta app.crypto.credentials-key (APP_CREDENTIALS_KEY): "
                    + "genere una con 'openssl rand -base64 32'");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("app.crypto.credentials-key debe estar en Base64", ex);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("app.crypto.credentials-key debe tener exactamente 32 bytes (AES-256)");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
