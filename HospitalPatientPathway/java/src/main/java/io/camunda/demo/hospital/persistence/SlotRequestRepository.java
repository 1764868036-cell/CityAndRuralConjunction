package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.SlotRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The waitlist requests of the pathway (table {@code slot_request}).
 *
 * <p>The retry loop of the model asks for a slot, is rejected, and asks again; the attempt counter
 * lives here, so the number of attempts survives the gaps between the jobs and the pathway can be
 * resumed without losing count.
 */
@Component
public class SlotRequestRepository extends JdbcRepository<SlotRequest, String> {

    SlotRequestRepository(JdbcClient jdbc) {
        super(jdbc, "slot_request", "slot_request_id");
    }

    /** Opens a slot request for a referral. */
    public void insert(SlotRequest request) {
        jdbc().sql("""
                INSERT INTO slot_request (slot_request_id, referral_id, service_code, earliest_date, latest_date,
                                          availability, attempt_count, status, requested_on, resolved_appointment_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(request.slotRequestId(), request.referralId(), request.serviceCode(),
                        request.earliestDate(), request.latestDate(), request.availability(),
                        request.attemptCount(), request.status(), request.requestedOn(),
                        request.resolvedAppointmentId())
                .update();
    }

    /** Overwrites the mutable fields of a request: the availability, the attempt count and the outcome. */
    public void update(SlotRequest request) {
        jdbc().sql("""
                UPDATE slot_request
                   SET referral_id = ?, service_code = ?, earliest_date = ?, latest_date = ?, availability = ?,
                       attempt_count = ?, status = ?, resolved_appointment_id = ?
                 WHERE slot_request_id = ?
                """)
                .params(request.referralId(), request.serviceCode(), request.earliestDate(), request.latestDate(),
                        request.availability(), request.attemptCount(), request.status(),
                        request.resolvedAppointmentId(), request.slotRequestId())
                .update();
    }

    /** The slot requests of one referral, oldest first. */
    public List<SlotRequest> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM slot_request WHERE referral_id = ? ORDER BY requested_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The request of a referral that is still being worked on, the newest first. */
    public Optional<SlotRequest> findOpenByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM slot_request WHERE referral_id = ? AND status = 'open' "
                        + "ORDER BY requested_on DESC FETCH FIRST 1 ROW ONLY")
                .param(referralId)
                .query(rows())
                .optional();
    }

    @Override
    protected SlotRequest mapRow(ResultSet row, int index) throws SQLException {
        return new SlotRequest(
                row.getString("slot_request_id"),
                row.getString("referral_id"),
                row.getString("service_code"),
                row.getObject("earliest_date", LocalDate.class),
                row.getObject("latest_date", LocalDate.class),
                row.getString("availability"),
                row.getInt("attempt_count"),
                row.getString("status"),
                row.getObject("requested_on", LocalDateTime.class),
                row.getString("resolved_appointment_id"));
    }
}
