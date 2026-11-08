package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.Amenity;
import com.ochavada.backend.catalog.RoomType;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Ficha pública de una tipología (página de detalle de la web). */
public record PublicRoomTypeDetail(
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
        BigDecimal fromPrice,
        String currency,
        List<PhotoResponse> photos,
        List<AmenityResponse> amenities) {

    public static PublicRoomTypeDetail from(RoomType rt) {
        return new PublicRoomTypeDetail(
                rt.getSlug(), rt.getName(), rt.getShortDescription(), rt.getDescription(), rt.getMaxOccupancy(),
                rt.getMaxAdults(), rt.getMaxChildren(), rt.getBedrooms(), rt.getBathrooms(),
                rt.getBedsDescription(), rt.getSizeM2(), rt.getBasePrice(), rt.getCurrency(),
                rt.orderedPhotos().stream().map(PhotoResponse::from).toList(),
                rt.getAmenities().stream()
                        .sorted(Comparator.comparing(Amenity::getCategory)
                                .thenComparing(a -> a.getName().toLowerCase(Locale.ROOT)))
                        .map(AmenityResponse::from)
                        .toList());
    }
}
