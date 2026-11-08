package com.ochavada.backend.pms;

import com.ochavada.backend.catalog.HousekeepingStatus;
import com.ochavada.backend.config.PropertyCalendar;
import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.housekeeping.HousekeepingTaskRepository;
import com.ochavada.backend.housekeeping.TaskStatus;
import com.ochavada.backend.pms.dto.DashboardResponse;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.reservation.backoffice.dto.ReservationSummary;
import com.ochavada.backend.ses.SesReportQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private static final List<ReservationStatus> ARRIVAL_STATUSES =
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);
    private static final List<ReservationStatus> DEPARTURE_STATUSES =
            List.of(ReservationStatus.CHECKED_IN, ReservationStatus.CHECKED_OUT);
    /** Reservas que generan ingreso de alojamiento (no se incluyen retenciones, cancelaciones ni no-shows). */
    private static final String REVENUE_STATUSES = "('CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT')";

    private final ReservationRepository reservationRepository;
    private final HousekeepingTaskRepository taskRepository;
    private final JdbcClient jdbc;
    private final PropertyCalendar calendar;
    private final PropertyProperties property;
    private final SesReportQueryService sesReports;

    public DashboardService(ReservationRepository reservationRepository, HousekeepingTaskRepository taskRepository,
                            JdbcClient jdbc, PropertyCalendar calendar, PropertyProperties property,
                            SesReportQueryService sesReports) {
        this.sesReports = sesReports;
        this.reservationRepository = reservationRepository;
        this.taskRepository = taskRepository;
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.property = property;
    }

    @Transactional(readOnly = true)
    public DashboardResponse summary(LocalDate requestedDate) {
        LocalDate date = requestedDate == null ? calendar.today() : requestedDate;
        LocalDate monthStart = date.withDayOfMonth(1);

        List<ReservationSummary> arrivals = reservationRepository
                .findByCheckInDateAndStatusInOrderByCode(date, ARRIVAL_STATUSES).stream()
                .map(ReservationSummary::from).toList();
        List<ReservationSummary> departures = reservationRepository
                .findByCheckOutDateAndStatusInOrderByCode(date, DEPARTURE_STATUSES).stream()
                .map(ReservationSummary::from).toList();

        int activeRooms = jdbc.sql("SELECT count(*) FROM room WHERE status = 'ACTIVE'").query(Integer.class).single();
        int occupied = jdbc.sql("""
                        SELECT count(DISTINCT room_id) FROM reservation
                         WHERE room_id IS NOT NULL AND status IN %s
                           AND check_in_date <= :d AND check_out_date > :d
                        """.formatted(REVENUE_STATUSES))
                .param("d", date).query(Integer.class).single();
        Map<String, Object> revenue = jdbc.sql("""
                        SELECT coalesce(sum(rn.price), 0) AS revenue, count(*) AS room_nights
                          FROM reservation_night rn JOIN reservation r ON r.id = rn.reservation_id
                         WHERE rn.stay_date BETWEEN :from AND :to AND r.status IN %s
                        """.formatted(REVENUE_STATUSES))
                .param("from", monthStart).param("to", date).query().singleRow();
        BigDecimal revenueMtd = ((BigDecimal) revenue.get("revenue")).setScale(2, RoundingMode.HALF_UP);
        long roomNights = ((Number) revenue.get("room_nights")).longValue();
        long daysElapsed = date.toEpochDay() - monthStart.toEpochDay() + 1;
        BigDecimal invoiced = jdbc.sql("""
                        SELECT coalesce(sum(total_amount), 0) FROM invoice WHERE issue_date BETWEEN :from AND :to
                        """)
                .param("from", monthStart).param("to", date).query(BigDecimal.class).single();

        Map<HousekeepingStatus, Long> byStatus = new EnumMap<>(HousekeepingStatus.class);
        jdbc.sql("SELECT housekeeping_status, count(*) AS n FROM room WHERE status <> 'INACTIVE' GROUP BY housekeeping_status")
                .query((rs, rowNum) -> byStatus.put(HousekeepingStatus.valueOf(rs.getString(1)), rs.getLong(2)))
                .list();

        return new DashboardResponse(
                date, arrivals, departures,
                reservationRepository.countByStatus(ReservationStatus.CHECKED_IN),
                reservationRepository.countByStatus(ReservationStatus.PENDING_PAYMENT),
                arrivals.stream().filter(a -> a.roomNumber() == null).count(),
                activeRooms, occupied,
                percent(occupied, activeRooms),
                revenueMtd,
                roomNights == 0 ? BigDecimal.ZERO.setScale(2)
                        : revenueMtd.divide(BigDecimal.valueOf(roomNights), 2, RoundingMode.HALF_UP),
                activeRooms == 0 ? BigDecimal.ZERO.setScale(2)
                        : revenueMtd.divide(BigDecimal.valueOf(activeRooms * daysElapsed), 2, RoundingMode.HALF_UP),
                invoiced.setScale(2, RoundingMode.HALF_UP),
                byStatus,
                taskRepository.countByStatusIn(List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS)),
                sesReports.needingAction(),
                sesReports.overdue(),
                property.currency());
    }

    private static BigDecimal percent(int part, int total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
