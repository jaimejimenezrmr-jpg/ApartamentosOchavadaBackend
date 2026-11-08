package com.ochavada.backend.guest.dto;

import com.ochavada.backend.guest.Guest;
import java.util.UUID;

public record GuestSummary(UUID id, String fullName, String email, String phone, String nationality,
                           String documentNumber) {

    public static GuestSummary from(Guest g) {
        return new GuestSummary(g.getId(), g.fullName(), g.getEmail(), g.getPhone(), g.getNationality(),
                g.getDocumentNumber());
    }
}
