package com.ochavada.backend.checkout;

import com.ochavada.backend.checkout.HoldReleaseService.ReleaseOutcome;
import com.ochavada.backend.reservation.ReservationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/** Libera las retenciones de checkout cuyo plazo de pago ha vencido. */
@Slf4j
@Service
public class HoldExpiryService {

    static final String EXPIRED_REASON = "Plazo de pago vencido";

    private final ReservationRepository reservationRepository;
    private final HoldReleaseService releaseService;
    private final Clock clock;

    public HoldExpiryService(ReservationRepository reservationRepository, HoldReleaseService releaseService,
                             Clock clock) {
        this.reservationRepository = reservationRepository;
        this.releaseService = releaseService;
        this.clock = clock;
    }

    /** @return retenciones liberadas */
    public int expireDueHolds(int max) {
        List<UUID> due = findDue(max);
        int released = 0;
        for (UUID reservationId : due) {
            try {
                if (releaseService.release(reservationId, EXPIRED_REASON, true) == ReleaseOutcome.RELEASED) {
                    released++;
                }
            } catch (RuntimeException ex) {
                // Stripe caído, por ejemplo: se reintentará en la siguiente pasada sin liberar la unidad.
                log.warn("No se pudo liberar la retención caducada {}: {}", reservationId, ex.getMessage());
            }
        }
        if (released > 0) {
            log.info("Liberadas {} retenciones de checkout caducadas", released);
        }
        return released;
    }

    private List<UUID> findDue(int max) {
        return reservationRepository.findExpiredHoldIds(clock.instant(), Limit.of(max));
    }
}
