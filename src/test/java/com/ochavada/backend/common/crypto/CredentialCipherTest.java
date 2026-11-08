package com.ochavada.backend.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final CredentialCipher cipher = new CredentialCipher(new CryptoProperties(KEY));

    @Test
    void roundTripsAndUsesRandomIv() {
        String a = cipher.encrypt("token-secreto");
        String b = cipher.encrypt("token-secreto");

        assertThat(a).isNotEqualTo(b).doesNotContain("token-secreto");
        assertThat(cipher.decrypt(a)).isEqualTo("token-secreto");
        assertThat(cipher.decrypt(b)).isEqualTo("token-secreto");
    }

    @Test
    void detectsTampering() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("token-secreto"));
        raw[raw.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongKey() {
        String encrypted = cipher.encrypt("token-secreto");
        CredentialCipher other = new CredentialCipher(new CryptoProperties(
                Base64.getEncoder().encodeToString("otra-clave-de-32-bytes-exactos!!".getBytes())));

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresAValidKeyOnlyWhenUsed() {
        CredentialCipher missing = new CredentialCipher(new CryptoProperties(null));
        assertThatThrownBy(() -> missing.encrypt("x")).hasMessageContaining("APP_CREDENTIALS_KEY");

        CredentialCipher shortKey = new CredentialCipher(new CryptoProperties("c2hvcnQ="));
        assertThatThrownBy(() -> shortKey.encrypt("x")).hasMessageContaining("32 bytes");
    }
}
