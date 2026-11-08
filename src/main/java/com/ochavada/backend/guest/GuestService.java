package com.ochavada.backend.guest;

import com.ochavada.backend.audit.AuditService;
import com.ochavada.backend.common.error.BadRequestException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import com.ochavada.backend.common.web.PageResponse;
import com.ochavada.backend.guest.dto.GuestDetail;
import com.ochavada.backend.guest.dto.GuestDetail.StayHistoryItem;
import com.ochavada.backend.guest.dto.GuestRequest;
import com.ochavada.backend.guest.dto.GuestSummary;
import com.ochavada.backend.reservation.ReservationStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_TERM_LENGTH = 2;

    private static final String STAY_HISTORY = """
            SELECT r.id, r.code, r.status, r.check_in_date, r.check_out_date, rt.name AS room_type_name,
                   r.total_amount, rg.is_holder
              FROM reservation_guest rg
              JOIN reservation r ON r.id = rg.reservation_id
              JOIN room_type rt ON rt.id = r.room_type_id
             WHERE rg.guest_id = :guestId
             ORDER BY r.check_in_date DESC
            """;

    private final GuestRepository repository;
    private final JdbcClient jdbc;
    private final AuditService auditService;

    public GuestService(GuestRepository repository, JdbcClient jdbc, AuditService auditService) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<GuestSummary> search(String query, int page, int size) {
        if (query == null || query.strip().length() < MIN_TERM_LENGTH) {
            throw new BadRequestException("guest.search_too_short",
                    "Indique al menos " + MIN_TERM_LENGTH + " caracteres para buscar");
        }
        String term = "%" + query.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("lastName1", "firstName"));
        return PageResponse.of(repository.search(term, pageable), GuestSummary::from);
    }

    @Transactional(readOnly = true)
    public GuestDetail get(UUID id) {
        return GuestDetail.from(find(id), history(id));
    }

    @Transactional
    public GuestDetail update(UUID id, GuestRequest r) {
        Guest g = find(id);
        g.assertVersion(r.version());
        g.setFirstName(r.firstName().trim());
        g.setLastName1(r.lastName1().trim());
        g.setLastName2(blankToNull(r.lastName2()));
        g.setSex(r.sex());
        g.setBirthDate(r.birthDate());
        g.setNationality(r.nationality());
        g.setDocumentType(r.documentType());
        g.setDocumentNumber(r.documentNumber() == null || r.documentNumber().isBlank()
                ? null : r.documentNumber().trim().toUpperCase(Locale.ROOT));
        g.setDocumentSupportNumber(blankToNull(r.documentSupportNumber()));
        g.setDocumentIssueDate(r.documentIssueDate());
        g.setEmail(r.email());
        g.setPhone(blankToNull(r.phone()));
        g.setPhoneLandline(blankToNull(r.phoneLandline()));
        g.setAddressLine(blankToNull(r.addressLine()));
        g.setAddressComplement(blankToNull(r.addressComplement()));
        g.setPostalCode(blankToNull(r.postalCode()));
        g.setCity(blankToNull(r.city()));
        g.setMunicipalityCode(r.municipalityCode());
        g.setProvince(blankToNull(r.province()));
        g.setCountryCode(r.countryCode());
        if (r.preferredLocale() != null) {
            g.setPreferredLocale(r.preferredLocale());
        }
        if (r.marketingConsent() != null) {
            g.setMarketingConsent(r.marketingConsent());
        }
        g.setNotes(blankToNull(r.notes()));
        repository.saveAndFlush(g);
        // Solo se registra qué se tocó, nunca los datos personales en sí.
        auditService.record("guest.updated", "guest", id, Map.of("fields", "profile"));
        return GuestDetail.from(g, history(id));
    }

    private List<StayHistoryItem> history(UUID guestId) {
        return jdbc.sql(STAY_HISTORY)
                .param("guestId", guestId)
                .query((rs, rowNum) -> new StayHistoryItem(
                        rs.getObject("id", UUID.class), rs.getString("code"),
                        ReservationStatus.valueOf(rs.getString("status")),
                        rs.getObject("check_in_date", LocalDate.class),
                        rs.getObject("check_out_date", LocalDate.class),
                        rs.getString("room_type_name"), rs.getBigDecimal("total_amount"), rs.getBoolean("is_holder")))
                .list();
    }

    private Guest find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("huésped", id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
