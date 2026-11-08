package com.ochavada.backend.pms.dto;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.reservation.backoffice.dto.ReservationSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Panel de recepción para un día.
 *
 * @param occupancyPercent   unidades ocupadas esa noche / unidades activas
 * @param revenueMonthToDate ingresos de alojamiento devengados (noches del mes hasta el día, incluido)
 * @param adrMonthToDate     tarifa media diaria (ingresos / noches vendidas)
 * @param revparMonthToDate  ingresos por unidad disponible (ingresos / (unidades × días))
 * @param invoicedMonthToDate total facturado en el mes (facturas y rectificativas)
 * @param sesReportsNeedingAction comunicaciones SES en borrador, rechazadas o con error
 * @param sesReportsOverdue    comunicaciones SES abiertas fuera del plazo legal de 24 h
 */
public record DashboardResponse(
        LocalDate date,
        List<ReservationSummary> arrivals,
        List<ReservationSummary> departures,
        long inHouse,
        long pendingPaymentHolds,
        long unassignedArrivals,
        int activeRooms,
        int occupiedRooms,
        BigDecimal occupancyPercent,
        BigDecimal revenueMonthToDate,
        BigDecimal adrMonthToDate,
        BigDecimal revparMonthToDate,
        BigDecimal invoicedMonthToDate,
        Map<HousekeepingStatus, Long> roomsByHousekeepingStatus,
        long openHousekeepingTasks,
        long sesReportsNeedingAction,
        long sesReportsOverdue,
        String currency) {
}
