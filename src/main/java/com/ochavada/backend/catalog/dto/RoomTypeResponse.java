package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.RoomType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Vista completa de una tipología para el backoffice. */
public record RoomTypeResponse(
        UUID id,
        String code,
        String slug,
        String name,
        String shortDescription,
        String description,
        int maxOccupancy,
        int maxAdults,
        int maxChildren,
        int bedrooms,
        int bathrooms,
        String bedsDescription,
        BigDecimal sizeM2,
        BigDecimal basePrice,
        String currency,
        int displayOrder,
        boolean active,
        Long beds24RoomId,
        String googleRoomId,
        List<PhotoResponse> photos,
        List<AmenityResponse> amenities,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static RoomTypeResponse from(RoomType rt) {
        return new RoomTypeResponse(
                rt.getId(), rt.getCode(), rt.getSlug(), rt.getName(), rt.getShortDescription(),
                rt.getDescription(), rt.getMaxOccupancy(), rt.getMaxAdults(), rt.getMaxChildren(),
                rt.getBedrooms(), rt.getBathrooms(), rt.getBedsDescription(), rt.getSizeM2(),
                rt.getBasePrice(), rt.getCurrency(), rt.getDisplayOrder(), rt.isActive(), rt.getBeds24RoomId(),
                rt.getGoogleRoomId(),
                rt.orderedPhotos().stream().map(PhotoResponse::from).toList(),
                rt.getAmenities().stream()
                        .sorted(Comparator.comparing(a -> a.getName().toLowerCase(Locale.ROOT)))
                        .map(AmenityResponse::from)
                        .toList(),
                rt.getVersion(), rt.getCreatedAt(), rt.getUpdatedAt());
    }
}
