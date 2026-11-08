package com.ochavada.backend.guestportal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestExchangeRequest(@NotBlank @Size(max = 100) String token) {

    @Override
    public String toString() {
        return "GuestExchangeRequest[token=****]";
    }
}
