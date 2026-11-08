package com.ochavada.backend.availability;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas de inventario en SQL nativo (modelo de lectura). Todas las fechas usan intervalos
 * semiabiertos {@code [entrada, salida)}: la noche del día de salida no se ocupa.
 * <p>
 * Ocupan inventario las reservas en estado {@code PENDING_PAYMENT}, {@code CONFIRMED} y
 * {@code CHECKED_IN}: exactamente los mismos estados que la restricción {@code ex_reservation_room_overlap},
 * de modo que la disponibilidad calculada y la que acepta la base de datos nunca divergen.
 * Las reservas sin unidad asignada (habituales al llegar de una OTA) restan inventario de su tipología.
 */
@Repository
public class InventoryRepository {

    static final String OCCUPYING_STATUSES = "('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')";

    private static final String FREE_ROOMS_FOR_STAY = """
            SELECT r.room_type_id, count(*) AS free_rooms
              FROM room r
             WHERE r.status = 'ACTIVE'
               AND r.room_type_id IN (:roomTypeIds)
               AND NOT EXISTS (SELECT 1 FROM reservation res
                                WHERE res.room_id = r.id
                                  AND res.status IN %s
                                  AND res.check_in_date < :checkOut AND res.check_out_date > :checkIn)
               AND NOT EXISTS (SELECT 1 FROM room_block b
                                WHERE b.room_id = r.id
                                  AND b.start_date < :checkOut AND b.end_date > :checkIn)
             GROUP BY r.room_type_id
            """.formatted(OCCUPYING_STATUSES);

    private static final String MAX_UNASSIGNED_PER_NIGHT = """
            SELECT x.room_type_id, max(x.cnt) AS max_unassigned
              FROM (SELECT res.room_type_id, n.night, count(*) AS cnt
                      FROM reservation res
                     CROSS JOIN LATERAL generate_series(
                               CAST(GREATEST(res.check_in_date, CAST(:checkIn AS date)) AS timestamp),
                               CAST(LEAST(res.check_out_date, CAST(:checkOut AS date)) - 1 AS timestamp),
                               interval '1 day') AS n(night)
                     WHERE res.room_id IS NULL
                       AND res.room_type_id IN (:roomTypeIds)
                       AND res.status IN %s
                       AND res.check_in_date < :checkOut AND res.check_out_date > :checkIn
                     GROUP BY res.room_type_id, n.night) x
             GROUP BY x.room_type_id
            """.formatted(OCCUPYING_STATUSES);

    private static final String NIGHTLY_AVAILABILITY = """
            SELECT CAST(n.night AS date) AS night,
                   (SELECT count(*) FROM room r
                     WHERE r.room_type_id = :roomTypeId AND r.status = 'ACTIVE'
                       AND NOT EXISTS (SELECT 1 FROM reservation res
                                        WHERE res.room_id = r.id AND res.status IN %1$s
                                          AND res.check_in_date <= CAST(n.night AS date)
                                          AND res.check_out_date > CAST(n.night AS date))
                       AND NOT EXISTS (SELECT 1 FROM room_block b
                                        WHERE b.room_id = r.id
                                          AND b.start_date <= CAST(n.night AS date)
                                          AND b.end_date > CAST(n.night AS date)))
                 - (SELECT count(*) FROM reservation res
                     WHERE res.room_id IS NULL AND res.room_type_id = :roomTypeId AND res.status IN %1$s
                       AND res.check_in_date <= CAST(n.night AS date)
                       AND res.check_out_date > CAST(n.night AS date)) AS available
              FROM generate_series(CAST(:fromDate AS timestamp),
                                   CAST(:toDate AS timestamp) - interval '1 day',
                                   interval '1 day') AS n(night)
             ORDER BY night
            """.formatted(OCCUPYING_STATUSES);

    private static final String FREE_ROOM_IDS_FOR_STAY = """
            SELECT r.id
              FROM room r
             WHERE r.status = 'ACTIVE'
               AND r.room_type_id = :roomTypeId
               AND NOT EXISTS (SELECT 1 FROM reservation res
                                WHERE res.room_id = r.id
                                  AND res.status IN %s
                                  AND res.check_in_date < :checkOut AND res.check_out_date > :checkIn)
               AND NOT EXISTS (SELECT 1 FROM room_block b
                                WHERE b.room_id = r.id
                                  AND b.start_date < :checkOut AND b.end_date > :checkIn)
             ORDER BY r.number
            """.formatted(OCCUPYING_STATUSES);

    private static final String ROOM_HAS_ACTIVE_RESERVATION = """
            SELECT EXISTS (SELECT 1 FROM reservation res
                            WHERE res.room_id = :roomId AND res.status IN %s
                              AND res.check_in_date < :endDate AND res.check_out_date > :startDate)
            """.formatted(OCCUPYING_STATUSES);

    private static final String ROOM_FREE_EXCLUDING = """
            SELECT r.status = 'ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM reservation res
                                WHERE res.room_id = r.id AND res.id <> :reservationId
                                  AND res.status IN %s
                                  AND res.check_in_date < :checkOut AND res.check_out_date > :checkIn)
               AND NOT EXISTS (SELECT 1 FROM room_block b
                                WHERE b.room_id = r.id
                                  AND b.start_date < :checkOut AND b.end_date > :checkIn)
              FROM room r
             WHERE r.id = :roomId
            """.formatted(OCCUPYING_STATUSES);

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Unidades de cada tipología libres durante toda la estancia (sin cambio de unidad a mitad de
     * estancia), descontando el pico de reservas sin unidad asignada en cualquiera de sus noches.
     */
    public Map<UUID, Integer> availableUnitsForStay(Collection<UUID> roomTypeIds, LocalDate checkIn,
                                                    LocalDate checkOut) {
        Map<UUID, Integer> result = new HashMap<>();
        if (roomTypeIds.isEmpty()) {
            return result;
        }
        Map<UUID, Integer> free = new HashMap<>();
        jdbc.sql(FREE_ROOMS_FOR_STAY)
                .param("roomTypeIds", roomTypeIds)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .query((rs, rowNum) -> free.put(rs.getObject("room_type_id", UUID.class), rs.getInt("free_rooms")))
                .list();
        Map<UUID, Integer> unassigned = new HashMap<>();
        jdbc.sql(MAX_UNASSIGNED_PER_NIGHT)
                .param("roomTypeIds", roomTypeIds)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .query((rs, rowNum) -> unassigned.put(rs.getObject("room_type_id", UUID.class),
                        rs.getInt("max_unassigned")))
                .list();
        for (UUID id : roomTypeIds) {
            int available = free.getOrDefault(id, 0) - unassigned.getOrDefault(id, 0);
            result.put(id, Math.max(available, 0));
        }
        return result;
    }

    /**
     * Unidades disponibles de una tipología noche a noche en {@code [from, to)}. Base del calendario de
     * tarifas y de la sincronización de inventario con el channel manager.
     */
    public Map<LocalDate, Integer> nightlyAvailability(UUID roomTypeId, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> result = new LinkedHashMap<>();
        if (!to.isAfter(from)) {
            return result;
        }
        jdbc.sql(NIGHTLY_AVAILABILITY)
                .param("roomTypeId", roomTypeId)
                .param("fromDate", from)
                .param("toDate", to)
                .query((rs, rowNum) -> result.put(rs.getObject("night", LocalDate.class),
                        Math.max(rs.getInt("available"), 0)))
                .list();
        return result;
    }

    /**
     * Unidades concretas libres toda la estancia, en orden de número. Es una instantánea: al asignar una,
     * la restricción {@code ex_reservation_room_overlap} decide en caso de carrera con otra reserva.
     */
    public List<UUID> freeRoomIdsForStay(UUID roomTypeId, LocalDate checkIn, LocalDate checkOut) {
        return jdbc.sql(FREE_ROOM_IDS_FOR_STAY)
                .param("roomTypeId", roomTypeId)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }

    public boolean roomHasActiveReservation(UUID roomId, LocalDate startDate, LocalDate endDate) {
        return Boolean.TRUE.equals(jdbc.sql(ROOM_HAS_ACTIVE_RESERVATION)
                .param("roomId", roomId)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .query(Boolean.class)
                .single());
    }

    /**
     * ¿Puede la reserva {@code reservationId} ocupar la unidad en {@code [checkIn, checkOut)}? La propia
     * reserva no cuenta como ocupación (cambio de fechas manteniendo la unidad).
     */
    public boolean roomFreeForStayExcluding(UUID roomId, LocalDate checkIn, LocalDate checkOut, UUID reservationId) {
        return Boolean.TRUE.equals(jdbc.sql(ROOM_FREE_EXCLUDING)
                .param("roomId", roomId)
                .param("checkIn", checkIn)
                .param("checkOut", checkOut)
                .param("reservationId", reservationId)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }
}
