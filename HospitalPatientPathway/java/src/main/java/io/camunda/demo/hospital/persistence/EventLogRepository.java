package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.EventLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The audit trail of the pathway (table {@code event_log}).
 *
 * <p>Append-only by design, like the access audit: the log records what a worker completed, what a
 * message carried and what an incident was, and it is the only place where the two systems' view of
 * an event can be compared afterwards.
 */
@Component
public class EventLogRepository extends JdbcRepository<EventLog, String> {

    EventLogRepository(JdbcClient jdbc) {
        super(jdbc, "event_log", "event_id");
    }

    /** Appends one event to the log. */
    public void append(EventLog event) {
        jdbc().sql("""
                INSERT INTO event_log (event_id, process_instance_key, element_id, job_type, message_name,
                                       event_type, referral_id, patient_id, occurred_on, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(event.eventId(), event.processInstanceKey(), event.elementId(), event.jobType(),
                        event.messageName(), event.eventType(), event.referralId(), event.patientId(),
                        event.occurredOn(), event.payload())
                .update();
    }

    /** The events of one referral, oldest first. */
    public List<EventLog> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM event_log WHERE referral_id = ? ORDER BY occurred_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The events of one Camunda instance, oldest first - the replay of a single pathway. */
    public List<EventLog> findByProcessInstanceKey(long processInstanceKey) {
        return jdbc().sql("SELECT * FROM event_log WHERE process_instance_key = ? ORDER BY occurred_on")
                .param(processInstanceKey)
                .query(rows())
                .list();
    }

    /** The BPMN elements that were recorded for a job type. */
    public List<EventLog> findByJobType(String jobType) {
        return jdbc().sql("SELECT * FROM event_log WHERE job_type = ? ORDER BY occurred_on")
                .param(jobType)
                .query(rows())
                .list();
    }

    @Override
    protected EventLog mapRow(ResultSet row, int index) throws SQLException {
        return new EventLog(
                row.getString("event_id"),
                row.getObject("process_instance_key", Long.class),
                row.getString("element_id"),
                row.getString("job_type"),
                row.getString("message_name"),
                row.getString("event_type"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getObject("occurred_on", LocalDateTime.class),
                row.getString("payload"));
    }
}
