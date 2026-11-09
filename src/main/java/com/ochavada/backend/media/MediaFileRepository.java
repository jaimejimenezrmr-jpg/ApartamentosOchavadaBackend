package com.ochavada.backend.media;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

    List<MediaFile> findByOwnerTypeAndOwnerIdOrderByCreatedAtAsc(MediaOwnerType ownerType, UUID ownerId);

    List<MediaFile> findByOwnerTypeAndOwnerIdInOrderByCreatedAtAsc(MediaOwnerType ownerType, Collection<UUID> ownerIds);

    long countByOwnerTypeAndOwnerId(MediaOwnerType ownerType, UUID ownerId);
}
