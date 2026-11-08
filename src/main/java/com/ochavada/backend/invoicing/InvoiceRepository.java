package com.ochavada.backend.invoicing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Page<Invoice> findByIssueDateBetween(LocalDate from, LocalDate to, Pageable pageable);

    @Query("""
            select count(i) > 0 from Invoice i
             where i.reservation.id = :reservationId
               and i.status = com.ochavada.backend.invoicing.Invoice.InvoiceStatus.ISSUED
               and i.invoiceType in (com.ochavada.backend.invoicing.InvoiceType.F1,
                                     com.ochavada.backend.invoicing.InvoiceType.F2)
            """)
    boolean existsActiveForReservation(@Param("reservationId") UUID reservationId);

    /** Huella del último registro de la cadena (orden de generación). */
    @Query(value = "SELECT verifactu_hash FROM invoice ORDER BY created_at DESC, id DESC LIMIT 1", nativeQuery = true)
    Optional<String> findLastHash();

    List<Invoice> findByRectifiedInvoiceId(UUID rectifiedInvoiceId);
}
