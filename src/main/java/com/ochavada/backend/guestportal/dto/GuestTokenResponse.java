package com.ochavada.backend.guestportal.dto;

import java.time.Instant;

public record GuestTokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
                                 String reservationCode) {
}
