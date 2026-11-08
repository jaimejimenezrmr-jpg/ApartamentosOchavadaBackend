package com.ochavada.backend.catalog.dto;

import com.ochavada.backend.catalog.RoomTypePhoto;
import java.util.UUID;

public record PhotoResponse(UUID id, String url, String altText, int displayOrder, boolean cover) {

    public static PhotoResponse from(RoomTypePhoto photo) {
        return new PhotoResponse(photo.getId(), photo.getUrl(), photo.getAltText(), photo.getDisplayOrder(),
                photo.isCover());
    }
}
