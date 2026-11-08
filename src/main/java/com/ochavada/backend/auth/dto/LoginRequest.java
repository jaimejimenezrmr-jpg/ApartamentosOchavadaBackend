package com.ochavada.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "El email es obligatorio")
        @Email(message = "El email no tiene un formato válido")
        @Size(max = 254, message = "El email no puede superar 254 caracteres")
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(max = 128, message = "La contraseña no puede superar 128 caracteres")
        String password) {

    public LoginRequest {
        // Tolera espacios accidentales al copiar/pegar el email (la contraseña nunca se modifica).
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        // Nunca registrar la contraseña en logs.
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
