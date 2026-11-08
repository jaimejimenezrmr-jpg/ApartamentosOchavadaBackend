package com.ochavada.backend.reservation.backoffice;

import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.payment.PaymentRepository;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationRepository;
import com.ochavada.backend.reservation.ReservationStatus;
import com.ochavada.backend.reservation.backoffice.dto.ReservationDetail;
import com.ochavada.backend.reservation.backoffice.dto.ReservationDetail.InvoiceRef;
import com.ochavada.backend.reservation.backoffice.dto.ReservationSummary;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationQueryService {

    static final int MAX_RANGE_DAYS = 400;
    static final int MAX_PAGE_SIZE = 100;

    private final ReservationRepository repository;
    private final PaymentRepository paymentRepository;
    private final JdbcClient jdbc;

    public ReservationQueryService(ReservationRepository repository, PaymentRepository paymentRepository,
                                   JdbcClient jdbc) {
        this.repository = repository;
        this.paymentRepository = paymentRepository;
        this.jdbc = jdbc;
    }

    /** Reservas que se solapan con {@code [from, to)}, por fecha de entrada. */
    @Transactional(readOnly = true)
    public PageResponse<ReservationSummary> search(LocalDate from, LocalDate to, Set<ReservationStatus> statuses,
                                                   String query, int page, int size) {
        if (!to.isAfter(from) || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new BadRequestException("validation.date_range",
                    "El rango debe ser positivo y no superar " + MAX_RANGE_DAYS + " días");
        }
        Set<ReservationStatus> effective = statuses == null || statuses.isEmpty()
                ? EnumSet.allOf(ReservationStatus.class) : statuses;
        String term = query == null || query.isBlank() ? "%"
                : "%" + query.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("checkInDate", "code"));
        return PageResponse.of(repository.search(from, to, effective, term, pageable), ReservationSummary::from);
    }

    @Transactional(readOnly = true)
    public ReservationDetail detail(UUID id) {
        Reservation r = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("reserva", id));
        return toDetail(r);
    }

    @Transactional(readOnly = true)
    public ReservationDetail detailByCode(String code) {
        Reservation r = repository.findByCode(code).orElseThrow(() -> new ResourceNotFoundException("reserva", code));
        return toDetail(r);
    }

    /** Debe llamarse dentro de una transacción (accede a asociaciones perezosas). */
    ReservationDetail toDetail(Reservation r) {
        List<InvoiceRef> invoices = jdbc.sql("""
                        SELECT id, full_number, invoice_type, status, issue_date, total_amount
                          FROM invoice WHERE reservation_id = :id ORDER BY created_at
                        """)
                .param("id", r.getId())
                .query((rs, rowNum) -> new InvoiceRef(rs.getObject("id", UUID.class), rs.getString("full_number"),
                        rs.getString("invoice_type"), rs.getString("status"),
                        rs.getObject("issue_date", LocalDate.class), rs.getBigDecimal("total_amount")))
                .list();
        return ReservationDetail.from(r, paymentRepository.findByReservationIdOrderByCreatedAtAsc(r.getId()), invoices);
    }
}
