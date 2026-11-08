package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.RoomType;
import java.math.BigDecimal;

/** Tarjeta de tipología para el listado público y los resultados de búsqueda. */
public record PublicRoomTypeSummary(
        String slug,
        String name,
        String shortDescription,
        int maxOccupancy,
        int maxAdults,
        int maxChildren,
        int bedrooms,
        int bathrooms,
        String bedsDescription,
        BigDecimal sizeM2,
        BigDecimal fromPrice,
        String currency,
        PhotoResponse coverPhoto) {

    public static PublicRoomTypeSummary from(RoomType rt) {
        return new PublicRoomTypeSummary(
                rt.getSlug(), rt.getName(), rt.getShortDescription(), rt.getMaxOccupancy(), rt.getMaxAdults(),
                rt.getMaxChildren(), rt.getBedrooms(), rt.getBathrooms(), rt.getBedsDescription(), rt.getSizeM2(),
                rt.getBasePrice(), rt.getCurrency(),
                rt.coverPhoto().map(PhotoResponse::from).orElse(null));
    }
}
