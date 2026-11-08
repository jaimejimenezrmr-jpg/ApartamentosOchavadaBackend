package com.ochavada.backend.channel.beds24;

import com.ochavada.backend.channel.SyncStateRepository;
import com.ochavada.backend.channel.beds24.Beds24BookingImporter.ImportResult;
import com.ochavada.backend.channel.beds24.Beds24Dtos.Booking;
import com.ochavada.backend.config.PropertyCalendar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Red de seguridad de los webhooks: consulta periódicamente las reservas modificadas en Beds24 desde la
 * última pasada (con un solapamiento) y las importa. Cada reserva se importa en su propia transacción; si
 * una falla, se deja en el outbox para reintentarla sin bloquear al resto ni perder la marca.
 */
@Slf4j
@Service
public class Beds24PollService {

    static final String MODIFIED_MARK = "beds24.bookings.modified";
    private static final Duration FIRST_POLL_LOOKBACK = Duration.ofDays(1);

    private final Beds24Properties properties;
    private final Beds24TokenManager tokenManager;
    private final Beds24Gateway gateway;
    private final Beds24BookingImporter importer;
    private final Beds24SyncListener syncListener;
    private final SyncStateRepository syncState;
    private final PropertyCalendar calendar;
    private final TransactionTemplate tx;
    private final TransactionTemplate itemTx;
    private final Clock clock;

    public Beds24PollService(Beds24Properties properties, Beds24TokenManager tokenManager, Beds24Gateway gateway,
                             Beds24BookingImporter importer, Beds24SyncListener syncListener,
                             SyncStateRepository syncState, PropertyCalendar calendar,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.gateway = gateway;
        this.importer = importer;
        this.syncListener = syncListener;
        this.syncState = syncState;
        this.calendar = calendar;
        this.tx = new TransactionTemplate(transactionManager);
        this.itemTx = new TransactionTemplate(transactionManager);
        this.itemTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** Sondeo incremental. No hace nada si Beds24 no está conectado u otra instancia está sondeando. */
    public PollSummary pollModified() {
        if (!tokenManager.isConnected()) {
            return PollSummary.EMPTY;
        }
        PollSummary summary = tx.execute(s -> {
            Instant startedAt = clock.instant();
            if (!syncState.tryLock(MODIFIED_MARK, startedAt.minus(FIRST_POLL_LOOKBACK))) {
                return null;
            }
            Instant since = syncState.lastSyncedAt(MODIFIED_MARK).orElse(startedAt).minus(properties.pollOverlap());
            PollSummary result = importAll(gateway.bookingsModifiedSince(since));
            syncState.update(MODIFIED_MARK, startedAt);
            return result;
        });
        if (summary == null) {
            return PollSummary.EMPTY;
        }
        if (summary.total() > 0) {
            log.info("Beds24: sondeo de reservas modificadas {}", summary.counts());
        }
        return summary;
    }

    /** Importación inicial: todas las reservas confirmadas con llegada desde hoy. */
    public PollSummary importUpcoming() {
        return importAll(gateway.bookingsArrivingFrom(calendar.today()));
    }

    /** Importación manual de una reserva concreta (botón «Reimportar» del backoffice). */
    public ImportResult importOne(long bookingId) {
        Booking booking = gateway.booking(bookingId).orElseThrow(() -> new Beds24Exception(
                "Beds24 no tiene la reserva " + bookingId, 404, null));
        return itemTx.execute(s -> importer.importBooking(booking));
    }

    private PollSummary importAll(List<Booking> bookings) {
        Map<ImportResult, Integer> counts = new EnumMap<>(ImportResult.class);
        int deferred = 0;
        for (Booking booking : bookings) {
            try {
                ImportResult result = itemTx.execute(s -> importer.importBooking(booking));
                counts.merge(result, 1, Integer::sum);
            } catch (RuntimeException ex) {
                deferred++;
                log.warn("Beds24: no se pudo importar la reserva {} ({}); se reintentará desde el outbox",
                        booking.id(), ex.getMessage());
                if (booking.id() != null) {
                    itemTx.executeWithoutResult(s -> syncListener.enqueueImport(booking.id(), null));
                }
            }
        }
        return new PollSummary(bookings.size(), counts, deferred);
    }

    public record PollSummary(int total, Map<ImportResult, Integer> counts, int deferred) {
        static final PollSummary EMPTY = new PollSummary(0, Map.of(), 0);
    }
}
