package com.ochavada.backend.catalog;

import com.ochavada.backend.availability.InventoryChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ochavada.backend.catalog.dto.AmenityAssignmentRequest;
import com.ochavada.backend.catalog.dto.PhotoRequest;
import com.ochavada.backend.catalog.dto.PublicRoomTypeDetail;
import com.ochavada.backend.catalog.dto.PublicRoomTypeSummary;
import com.ochavada.backend.catalog.dto.RoomTypeRequest;
import com.ochavada.backend.catalog.dto.RoomTypeResponse;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.common.text.Slugs;
import com.ochavada.backend.config.PropertyProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestión de tipologías (backoffice) y su vista pública (catálogo de la web).
 */
@Service
public class RoomTypeService {

    static final int MAX_PHOTOS = 40;

    private final RoomTypeRepository repository;
    private final RoomTypePhotoRepository photoRepository;
    private final AmenityRepository amenityRepository;
    private final RoomRepository roomRepository;
    private final String currency;
    private final ApplicationEventPublisher events;

    public RoomTypeService(RoomTypeRepository repository, RoomTypePhotoRepository photoRepository,
                           AmenityRepository amenityRepository, RoomRepository roomRepository,
                           PropertyProperties propertyProperties, ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.photoRepository = photoRepository;
        this.amenityRepository = amenityRepository;
        this.roomRepository = roomRepository;
        this.currency = propertyProperties.currency();
    }

    // ------------------------------------------------------------------ backoffice

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> list() {
        return repository.findAllByOrderByDisplayOrderAscNameAsc().stream().map(RoomTypeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RoomTypeResponse get(UUID id) {
        return RoomTypeResponse.from(find(id));
    }

    @Transactional
    public RoomTypeResponse create(RoomTypeRequest request) {
        String slug = resolveSlug(request);
        if (repository.existsByCode(request.code())) {
            throw new ConflictException("room_type.duplicate_code", "Ya existe una tipología con el código " + request.code());
        }
        if (repository.existsBySlug(slug)) {
            throw duplicateSlug(slug);
        }
        if (request.beds24RoomId() != null && repository.existsByBeds24RoomId(request.beds24RoomId())) {
            throw duplicateBeds24(request.beds24RoomId());
        }
        RoomType roomType = new RoomType(request.code(), slug, request.name().trim(), currency);
        apply(roomType, request, slug);
        RoomTypeResponse response = RoomTypeResponse.from(repository.saveAndFlush(roomType));
        if (roomType.getBeds24RoomId() != null) {
            events.publishEvent(InventoryChangedEvent.fullHorizon(roomType.getId()));
        }
        return response;
    }

    @Transactional
    public RoomTypeResponse update(UUID id, RoomTypeRequest request) {
        RoomType roomType = find(id);
        roomType.assertVersion(request.version());
        String slug = resolveSlug(request);
        if (repository.existsByCodeAndIdNot(request.code(), id)) {
            throw new ConflictException("room_type.duplicate_code", "Ya existe una tipología con el código " + request.code());
        }
        if (repository.existsBySlugAndIdNot(slug, id)) {
            throw duplicateSlug(slug);
        }
        if (request.beds24RoomId() != null && repository.existsByBeds24RoomIdAndIdNot(request.beds24RoomId(), id)) {
            throw duplicateBeds24(request.beds24RoomId());
        }
        Long previousBeds24RoomId = roomType.getBeds24RoomId();
        boolean wasActive = roomType.isActive();
        roomType.setCode(request.code());
        apply(roomType, request, slug);
        RoomTypeResponse response = RoomTypeResponse.from(repository.saveAndFlush(roomType));
        if (!java.util.Objects.equals(previousBeds24RoomId, roomType.getBeds24RoomId()) || wasActive != roomType.isActive()) {
            // Nueva vinculación con Beds24 o (des)activación: se reenvía todo el horizonte.
            events.publishEvent(InventoryChangedEvent.fullHorizon(roomType.getId()));
        }
        return response;
    }

    @Transactional
    public void delete(UUID id) {
        RoomType roomType = find(id);
        if (roomRepository.existsByRoomTypeId(id)) {
            throw new ConflictException("room_type.has_rooms",
                    "La tipología tiene unidades asociadas. Elimínelas o desactive la tipología");
        }
        repository.delete(roomType);
        repository.flush();
    }

    @Transactional
    public RoomTypeResponse addPhoto(UUID roomTypeId, PhotoRequest request) {
        RoomType roomType = find(roomTypeId);
        if (roomType.getPhotos().size() >= MAX_PHOTOS) {
            throw new BadRequestException("room_type.too_many_photos",
                    "Una tipología admite como máximo " + MAX_PHOTOS + " fotos");
        }
        boolean firstPhoto = roomType.getPhotos().isEmpty();
        RoomTypePhoto photo = roomType.addPhoto(request.url(), request.altText().trim(), request.displayOrder());
        // Se persiste la foto directamente: un merge de la tipología la copiaría y esta referencia
        // dejaría de ser la instancia gestionada.
        photoRepository.saveAndFlush(photo);
        if (request.cover() || firstPhoto) {
            makeCover(roomType, photo);
        }
        return RoomTypeResponse.from(roomType);
    }

    @Transactional
    public RoomTypeResponse updatePhoto(UUID roomTypeId, UUID photoId, PhotoRequest request) {
        RoomType roomType = find(roomTypeId);
        RoomTypePhoto photo = findPhoto(roomType, photoId);
        photo.setUrl(request.url());
        photo.setAltText(request.altText().trim());
        photo.setDisplayOrder(request.displayOrder());
        if (request.cover() && !photo.isCover()) {
            makeCover(roomType, photo);
        } else if (!request.cover() && photo.isCover()) {
            photo.setCover(false);
        }
        repository.flush();
        return RoomTypeResponse.from(roomType);
    }

    @Transactional
    public RoomTypeResponse removePhoto(UUID roomTypeId, UUID photoId) {
        RoomType roomType = find(roomTypeId);
        RoomTypePhoto photo = findPhoto(roomType, photoId);
        boolean wasCover = photo.isCover();
        roomType.removePhoto(photo);
        repository.flush();
        if (wasCover) {
            roomType.getPhotos().stream().findFirst().ifPresent(next -> makeCover(roomType, next));
        }
        return RoomTypeResponse.from(roomType);
    }

    @Transactional
    public RoomTypeResponse replaceAmenities(UUID roomTypeId, AmenityAssignmentRequest request) {
        RoomType roomType = find(roomTypeId);
        List<Amenity> amenities = amenityRepository.findAllById(request.amenityIds());
        if (amenities.size() != request.amenityIds().size()) {
            Set<UUID> missing = new HashSet<>(request.amenityIds());
            amenities.forEach(a -> missing.remove(a.getId()));
            throw new BadRequestException("room_type.unknown_amenities", "Hay servicios inexistentes en la lista",
                    Map.of("unknownAmenityIds", missing));
        }
        roomType.replaceAmenities(new HashSet<>(amenities));
        repository.flush();
        return RoomTypeResponse.from(roomType);
    }

    // ------------------------------------------------------------------ público

    @Transactional(readOnly = true)
    public List<PublicRoomTypeSummary> publicCatalog() {
        return repository.findByActiveTrueOrderByDisplayOrderAscNameAsc().stream()
                .map(PublicRoomTypeSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicRoomTypeDetail publicDetail(String slug) {
        return repository.findBySlugAndActiveTrue(slug)
                .map(PublicRoomTypeDetail::from)
                .orElseThrow(() -> new ResourceNotFoundException("tipología", slug));
    }

    // ------------------------------------------------------------------ internos

    RoomType find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("tipología", id));
    }

    private void makeCover(RoomType roomType, RoomTypePhoto photo) {
        photoRepository.clearCoverExcept(roomType.getId(), photo.getId());
        roomType.getPhotos().stream().filter(p -> !p.equals(photo)).forEach(p -> p.setCover(false));
        photo.setCover(true);
        photoRepository.flush();
    }

    private static RoomTypePhoto findPhoto(RoomType roomType, UUID photoId) {
        return roomType.getPhotos().stream()
                .filter(p -> p.getId().equals(photoId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("foto", photoId));
    }

    private static void apply(RoomType rt, RoomTypeRequest r, String slug) {
        rt.setSlug(slug);
        rt.setName(r.name().trim());
        rt.setShortDescription(blankToNull(r.shortDescription()));
        rt.setDescription(blankToNull(r.description()));
        rt.setMaxOccupancy(r.maxOccupancy());
        rt.setMaxAdults(r.maxAdults());
        rt.setMaxChildren(r.maxChildren());
        rt.setBedrooms(r.bedrooms());
        rt.setBathrooms(r.bathrooms());
        rt.setBedsDescription(blankToNull(r.bedsDescription()));
        rt.setSizeM2(r.sizeM2());
        rt.setBasePrice(r.basePrice());
        rt.setDisplayOrder(r.displayOrder());
        rt.setActive(r.active());
        rt.setBeds24RoomId(r.beds24RoomId());
        rt.setGoogleRoomId(blankToNull(r.googleRoomId()));
    }

    private static String resolveSlug(RoomTypeRequest request) {
        String slug = request.slug() == null || request.slug().isBlank()
                ? Slugs.slugify(request.name())
                : request.slug();
        if (slug.isEmpty()) {
            throw new BadRequestException("room_type.invalid_slug",
                    "No se puede generar un slug a partir del nombre; indique uno explícitamente");
        }
        return slug;
    }

    private static ConflictException duplicateSlug(String slug) {
        return new ConflictException("room_type.duplicate_slug", "Ya existe una tipología con el slug " + slug);
    }

    private static ConflictException duplicateBeds24(Long id) {
        return new ConflictException("room_type.duplicate_beds24_id",
                "Ya existe una tipología vinculada a la habitación de Beds24 " + id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
