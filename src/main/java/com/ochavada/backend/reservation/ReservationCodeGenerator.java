package com.ochavada.backend.reservation;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Localizadores de reserva legibles por teléfono: {@code OCH-7KQ4MZ}. El alfabeto excluye caracteres
 * ambiguos (0/O, 1/I/L). 30^6 ≈ 729 millones de combinaciones.
 */
@Component
public class ReservationCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ2345679".toCharArray();
    private static final int LENGTH = 6;
    private static final int MAX_ATTEMPTS = 10;

    private final SecureRandom random = new SecureRandom();
    private final ReservationRepository repository;

    public ReservationCodeGenerator(ReservationRepository repository) {
        this.repository = repository;
    }

    public String nextCode() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!repository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No se pudo generar un localizador de reserva único");
    }

    String randomCode() {
        StringBuilder sb = new StringBuilder("OCH-");
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
