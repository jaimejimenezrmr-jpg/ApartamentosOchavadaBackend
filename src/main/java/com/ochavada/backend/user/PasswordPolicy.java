package com.ochavada.backend.user;

import com.ochavada.backend.common.error.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reglas de contraseña del personal y generación de contraseñas temporales.
 * <ul>
 *     <li>Entre {@value #MIN_LENGTH} y {@value #MAX_LENGTH} caracteres y, como mucho, 72 bytes (límite de BCrypt).</li>
 *     <li>Sin el nombre del email ni un único carácter repetido.</li>
 * </ul>
 * Se prima la longitud sobre las reglas de composición (recomendación de NIST SP 800-63B).
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 64;
    private static final int BCRYPT_MAX_BYTES = 72;
    /** Sin caracteres que se confunden al dictarla (0/O, 1/l/I). */
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TEMPORARY_GROUPS = 4;
    private static final int TEMPORARY_GROUP_LENGTH = 4;

    private final SecureRandom random = new SecureRandom();

    /** @throws BadRequestException con el error asociado al campo {@code field} si no cumple las reglas */
    public void validate(String password, String email, String field) {
        String problem = problem(password, email);
        if (problem != null) {
            throw new BadRequestException("auth.weak_password", problem,
                    Map.of("errors", List.of(Map.of("field", field, "message", problem))));
        }
    }

    /** Contraseña temporal legible (p. ej. {@code Kf7q-pX2m-9tRb-hW4z}): 16 caracteres aleatorios, ~92 bits. */
    public String generateTemporary() {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < TEMPORARY_GROUPS; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < TEMPORARY_GROUP_LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return sb.toString();
    }

    static String problem(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "La contraseña debe tener al menos " + MIN_LENGTH + " caracteres";
        }
        if (password.length() > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            return "La contraseña no puede superar " + MAX_LENGTH + " caracteres";
        }
        if (password.chars().distinct().count() == 1) {
            return "La contraseña no puede ser un único carácter repetido";
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local)) {
                return "La contraseña no puede contener su email";
            }
        }
        return null;
    }
}
