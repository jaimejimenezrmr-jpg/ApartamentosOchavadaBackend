package com.ochavada.backend.catalog;

import com.ochavada.backend.common.domain.VersionedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tipología comercial: lo que se muestra en la web y se vende en las OTAs (p. ej. "Apartamento
 * dúplex 2 dormitorios"). Agrupa una o varias unidades físicas ({@link Room}).
 */
@Entity
@Table(name = "room_type")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomType extends VersionedEntity {

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "slug", nullable = false, length = 120)
    private String slug;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_occupancy", nullable = false)
    private int maxOccupancy;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_adults", nullable = false)
    private int maxAdults;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "max_children", nullable = false)
    private int maxChildren;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "bedrooms", nullable = false)
    private int bedrooms;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "bathrooms", nullable = false)
    private int bathrooms;

    @Column(name = "beds_description", length = 200)
    private String bedsDescription;

    @Column(name = "size_m2", precision = 6, scale = 2)
    private BigDecimal sizeM2;

    /** Precio orientativo "desde" para el catálogo; el precio real lo calcula el motor de tarifas. */
    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "beds24_room_id")
    private Long beds24RoomId;

    @Column(name = "google_room_id", length = 100)
    private String googleRoomId;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "roomType", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<RoomTypePhoto> photos = new ArrayList<>();

    @Setter(AccessLevel.NONE)
    @ManyToMany
    @JoinTable(name = "room_type_amenity",
            joinColumns = @JoinColumn(name = "room_type_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    private Set<Amenity> amenities = new LinkedHashSet<>();

    public RoomType(String code, String slug, String name, String currency) {
        this.code = code;
        this.slug = slug;
        this.name = name;
        this.currency = currency;
        this.active = true;
    }

    public RoomTypePhoto addPhoto(String url, String altText, int displayOrder) {
        RoomTypePhoto photo = new RoomTypePhoto(this, url, altText, displayOrder);
        photos.add(photo);
        return photo;
    }

    public void removePhoto(RoomTypePhoto photo) {
        photos.remove(photo);
    }

    /**
     * Fotos por orden de visualización. {@code @OrderBy} solo ordena al cargar desde base de datos; las
     * fotos añadidas en la misma transacción se ordenan aquí.
     */
    public List<RoomTypePhoto> orderedPhotos() {
        return photos.stream().sorted(Comparator.comparingInt(RoomTypePhoto::getDisplayOrder)).toList();
    }

    public Optional<RoomTypePhoto> coverPhoto() {
        return photos.stream()
                .filter(RoomTypePhoto::isCover)
                .findFirst()
                .or(() -> photos.stream().min(Comparator.comparingInt(RoomTypePhoto::getDisplayOrder)));
    }

    public void replaceAmenities(Set<Amenity> newAmenities) {
        amenities.clear();
        amenities.addAll(newAmenities);
    }

    /** ¿Puede alojar a este grupo? */
    public boolean accommodates(int adults, int children) {
        return adults >= 1 && children >= 0
                && adults <= maxAdults
                && children <= maxChildren
                && adults + children <= maxOccupancy;
    }
}
