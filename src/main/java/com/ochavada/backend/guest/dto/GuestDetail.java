package com.ochavada.backend.guest.dto;

import com.ochavada.backend.guest.DocumentType;
import com.ochavada.backend.guest.Guest;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Ficha completa del huésped con su historial de estancias (más recientes primero). */
public record GuestDetail(
        UUID id,
        String firstName,
        String lastName1,
        String lastName2,
        String sex,
        LocalDate birthDate,
        String nationality,
        DocumentType documentType,
        String documentNumber,
        String documentSupportNumber,
        LocalDate documentIssueDate,
        String email,
        String phone,
        String phoneLandline,
        String addressLine,
        String addressComplement,
        String postalCode,
        String city,
        String municipalityCode,
        String province,
        String countryCode,
        String preferredLocale,
        boolean marketingConsent,
        Instant privacyAcceptedAt,
        String notes,
        long version,
        List<StayHistoryItem> stays) {

    public record StayHistoryItem(UUID reservationId, String code, ReservationStatus status, LocalDate checkIn,
                                  LocalDate checkOut, String roomTypeName, BigDecimal totalAmount, boolean holder) {
    }

    public static GuestDetail from(Guest g, List<StayHistoryItem> stays) {
        return new GuestDetail(g.getId(), g.getFirstName(), g.getLastName1(), g.getLastName2(), g.getSex(),
                g.getBirthDate(), g.getNationality(), g.getDocumentType(), g.getDocumentNumber(),
                g.getDocumentSupportNumber(), g.getDocumentIssueDate(), g.getEmail(), g.getPhone(),
                g.getPhoneLandline(), g.getAddressLine(), g.getAddressComplement(), g.getPostalCode(), g.getCity(),
                g.getMunicipalityCode(), g.getProvince(), g.getCountryCode(), g.getPreferredLocale(),
                g.isMarketingConsent(), g.getPrivacyAcceptedAt(), g.getNotes(), g.getVersion(), stays);
    }
}
