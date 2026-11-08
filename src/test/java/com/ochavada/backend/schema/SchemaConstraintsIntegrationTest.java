package com.ochavada.backend.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ochavada.backend.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifica las garantías que el esquema impone a nivel de base de datos, independientemente del código.
 */
class SchemaConstraintsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID roomTypeId;
    private UUID roomId;
    private UUID guestId;

    @BeforeEach
    void seedCatalog() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        roomTypeId = jdbc.queryForObject("""
                insert into room_type (code, slug, name, max_occupancy, max_adults, base_price)
                values (?, ?, 'Apartamento Dúplex', 4, 4, 120.00) returning id
                """, UUID.class, "DUP-" + suffix, "duplex-" + suffix);
        roomId = jdbc.queryForObject(
                "insert into room (room_type_id, number) values (?, ?) returning id",
                UUID.class, roomTypeId, suffix.substring(0, 6));
        guestId = jdbc.queryForObject("""
                insert into guest (first_name, last_name1, email, document_type, document_number, nationality)
                values ('Lucía', 'García', 'lucia@example.com', 'NIF', '12345678Z', 'ESP') returning id
                """, UUID.class);
    }

    @Test
    void flywayAppliedInitialMigration() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(versions).contains("1");
    }

    @Test
    void overlappingActiveReservationsOnSameRoomAreRejected() {
        insertReservation("CONFIRMED", LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 15));

        assertThatThrownBy(() -> insertReservation("CONFIRMED", LocalDate.of(2027, 3, 14), LocalDate.of(2027, 3, 18)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_reservation_room_overlap");
    }

    @Test
    void backToBackReservationsAreAllowed() {
        insertReservation("CONFIRMED", LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 5));

        // El día de salida de una es el día de llegada de la siguiente: intervalo semiabierto [in, out).
        assertThatCode(() -> insertReservation("CONFIRMED", LocalDate.of(2027, 4, 5), LocalDate.of(2027, 4, 8)))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledReservationsDoNotBlockAvailability() {
        UUID cancelled = insertReservation("CONFIRMED", LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 5));
        jdbc.update("update reservation set status = 'CANCELLED', cancelled_at = now() where id = ?", cancelled);

        assertThatCode(() -> insertReservation("CONFIRMED", LocalDate.of(2027, 5, 2), LocalDate.of(2027, 5, 4)))
                .doesNotThrowAnyException();
    }

    @Test
    void generatedNightsColumnIsComputed() {
        UUID id = insertReservation("CONFIRMED", LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 4));

        assertThat(jdbc.queryForObject("select nights from reservation where id = ?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void checkedInReservationRequiresTimestampAndRoom() {
        assertThatThrownBy(() -> insertReservation("CHECKED_IN", LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 3)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_checked_in");
    }

    @Test
    void overlappingRoomBlocksAreRejected() {
        jdbc.update("insert into room_block (room_id, start_date, end_date, block_type) values (?, ?, ?, 'MAINTENANCE')",
                roomId, LocalDate.of(2027, 8, 1), LocalDate.of(2027, 8, 10));

        assertThatThrownBy(() -> jdbc.update(
                "insert into room_block (room_id, start_date, end_date, block_type) values (?, ?, ?, 'OWNER_USE')",
                roomId, LocalDate.of(2027, 8, 9), LocalDate.of(2027, 8, 12)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roomLockMustReferenceItsRoom() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into lock_device (external_lock_id, name, lock_type) values (?, 'Puerta 1A', 'ROOM')",
                System.nanoTime()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_lock_device_room");

        assertThatCode(() -> jdbc.update(
                "insert into lock_device (external_lock_id, name, lock_type) values (?, 'Portal', 'MAIN_ENTRANCE')",
                System.nanoTime()))
                .doesNotThrowAnyException();
    }

    @Test
    void lockAccessLogIsAppendOnly() {
        UUID reservationId = insertReservation("CONFIRMED", LocalDate.of(2027, 9, 1), LocalDate.of(2027, 9, 3));
        UUID lockId = jdbc.queryForObject(
                "insert into lock_device (external_lock_id, name, lock_type, room_id) values (?, 'Puerta', 'ROOM', ?) returning id",
                UUID.class, System.nanoTime(), roomId);
        Long logId = jdbc.queryForObject("""
                insert into lock_access_log (lock_device_id, reservation_id, actor_type, action, result, denial_reason, client_ip)
                values (?, ?, 'GUEST', 'REMOTE_UNLOCK', 'DENIED', 'RESERVATION_NOT_CHECKED_IN', '203.0.113.7'::inet)
                returning id
                """, Long.class, lockId, reservationId);

        assertThatThrownBy(() -> jdbc.update("update lock_access_log set result = 'SUCCESS' where id = ?", logId))
                .rootCause().hasMessageContaining("lock_access_log").hasMessageContaining("UPDATE");
        assertThatThrownBy(() -> jdbc.update("delete from lock_access_log where id = ?", logId))
                .rootCause().hasMessageContaining("lock_access_log").hasMessageContaining("DELETE");
    }

    @Test
    void issuedInvoicesAreImmutable() {
        String seriesCode = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID seriesId = jdbc.queryForObject(
                "insert into invoice_series (code, fiscal_year, invoice_type) values (?, 2027, 'F2') returning id",
                UUID.class, seriesCode);
        UUID invoiceId = jdbc.queryForObject("""
                insert into invoice (series_id, number, full_number, invoice_type, issue_date, issuer_name,
                                     issuer_tax_id, issuer_address, taxable_base, vat_amount, total_amount)
                values (?, 1, ?, 'F2', DATE '2027-01-15', 'Apartamentos Ochavada S.L.', 'B00000000',
                        'Calle Pescaderías 6', 100.00, 10.00, 110.00)
                returning id
                """, UUID.class, seriesId, seriesCode + "-2027-000001");

        assertThatThrownBy(() -> jdbc.update("update invoice set total_amount = 1 where id = ?", invoiceId))
                .rootCause().hasMessageContaining("inmutables");
        assertThatThrownBy(() -> jdbc.update("delete from invoice where id = ?", invoiceId))
                .rootCause().hasMessageContaining("no pueden eliminarse");
        // El seguimiento VERI*FACTU sí puede actualizarse.
        assertThatCode(() -> jdbc.update("update invoice set verifactu_status = 'PENDING' where id = ?", invoiceId))
                .doesNotThrowAnyException();
    }

    @Test
    void sesTravelerRequiresSupportNumberForSpanishDocuments() {
        UUID reservationId = insertReservation("CONFIRMED", LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 3));
        UUID reportId = jdbc.queryForObject("""
                insert into traveler_report (reservation_id, communication_type, operation, establishment_code,
                                             contract_reference, contract_date, check_in_at, check_out_at,
                                             num_travelers, deadline_at)
                values (?, 'TRAVELER_ENTRY', 'A', '0000000001', 'OCH-TEST', DATE '2027-09-01',
                        TIMESTAMPTZ '2027-10-01 15:00:00+00', TIMESTAMPTZ '2027-10-03 10:00:00+00', 1,
                        TIMESTAMPTZ '2027-10-02 15:00:00+00')
                returning id
                """, UUID.class, reservationId);

        assertThatThrownBy(() -> jdbc.update("""
                insert into traveler_report_person (report_id, role, first_name, last_name1, last_name2, sex,
                                                    birth_date, nationality, document_type, document_number,
                                                    address_line, postal_code, municipality_code, country_code, email)
                values (?, 'TI', 'Lucía', 'García', 'López', 'M', DATE '1990-05-05', 'ESP', 'NIF', '12345678Z',
                        'Calle Mayor 1', '14920', '14002', 'ESP', 'lucia@example.com')
                """, reportId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trp_support_number");
    }

    private UUID insertReservation(String status, LocalDate checkIn, LocalDate checkOut) {
        String code = "OCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return jdbc.queryForObject("""
                insert into reservation (code, status, channel, room_type_id, room_id, holder_guest_id,
                                         check_in_date, check_out_date, adults, total_amount, payment_mode)
                values (?, ?, 'DIRECT', ?, ?, ?, ?, ?, 2, 360.00, 'ONLINE')
                returning id
                """, UUID.class, code, status, roomTypeId, roomId, guestId, checkIn, checkOut);
    }
}
