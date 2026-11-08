package com.ochavada.backend.availability;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Evento de dominio publicado (de forma síncrona, dentro de la transacción del cambio) cuando cambia la
 * disponibilidad, el precio o las restricciones de una o varias tipologías: reservas, retenciones,
 * bloqueos, unidades, planes, temporadas y ajustes diarios. El channel manager lo traduce en una
 * sincronización de inventario hacia las OTAs.
 *
 * @param roomTypeIds tipologías afectadas
 * @param from        primera noche afectada; {@code null} = desde hoy
 * @param to          noche siguiente a la última afectada (exclusiva); {@code null} = todo el horizonte
 */
public record InventoryChangedEvent(Set<UUID> roomTypeIds, LocalDate from, LocalDate to) {

    public InventoryChangedEvent {
        roomTypeIds = Set.copyOf(roomTypeIds.stream().filter(Objects::nonNull).toList());
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("Rango de noches vacío: " + from + " → " + to);
        }
    }

    /** Noches {@code [from, to)} de una tipología. */
    public static InventoryChangedEvent nights(UUID roomTypeId, LocalDate from, LocalDate to) {
        return new InventoryChangedEvent(Set.of(roomTypeId), from, to);
    }

    /** Noches {@code [from, to)} de varias tipologías (p. ej. cambio de unidad entre tipologías). */
    public static InventoryChangedEvent nights(Collection<UUID> roomTypeIds, LocalDate from, LocalDate to) {
        return new InventoryChangedEvent(new LinkedHashSet<>(roomTypeIds), from, to);
    }

    /** Todo el horizonte de venta (cambios de plan, de unidades o de vinculación). */
    public static InventoryChangedEvent fullHorizon(UUID roomTypeId) {
        return new InventoryChangedEvent(Set.of(roomTypeId), null, null);
    }

    /** Ajustes del día {@code from} al día {@code toInclusive}, ambos incluidos. */
    public static InventoryChangedEvent days(UUID roomTypeId, LocalDate from, LocalDate toInclusive) {
        return new InventoryChangedEvent(Set.of(roomTypeId), from, toInclusive.plusDays(1));
    }
}
