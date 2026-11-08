package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.Amenity;
import com.ochavada.backend.catalog.AmenityCategory;
import java.util.UUID;

public record AmenityResponse(UUID id, String code, String name, String icon, AmenityCategory category) {

    public static AmenityResponse from(Amenity amenity) {
        return new AmenityResponse(amenity.getId(), amenity.getCode(), amenity.getName(), amenity.getIcon(),
                amenity.getCategory());
    }
}
